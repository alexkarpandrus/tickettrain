(ns ttt.providers.tracker.taskwarrior
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]))

(def annotation-prefix "<!-- ttt:description -->\n")
(def urgent-priority-prefix "<!-- ttt:priority -->urgent")
(def waiting-state-marker "<!-- ttt:state -->waiting")
(def task-date-formatter
  (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'" java.util.Locale/ROOT))

(defn command-args
  [app-config args]
  (into ["task"]
        (concat args
                (when-let [taskrc (get-in app-config [:tracker :taskrc])]
                  [(str "rc:" taskrc)]))))

(defn task-run
  [app-config & args]
  (apply shell/run (command-args app-config args)))


(defn task-version
  []
  (shell/run "task" "--version"))

(defn task-run-input
  [app-config input & args]
  (apply shell/run-input input (command-args app-config args)))

(defn task-json
  [app-config & args]
  (let [output (apply task-run app-config args)]
    (if (str/blank? output)
      []
      (let [value (json/parse-string output true)]
        (if (sequential? value) (vec value) [value])))))

(defn export-tasks
  ([app-config]
   (task-json app-config "export" "rc.json.array=on"))
  ([app-config reference]
   (task-json app-config reference "export" "rc.json.array=on")))

(defn configured-scope
  [app-config]
  (let [location (str/trim (task-run app-config "_get" "rc.data.location"))]
    (when (str/blank? location)
      (throw (ex-info "Taskwarrior did not report rc.data.location."
                      {:code :provider-config-invalid :provider :taskwarrior})))
    (domain/scope-identity :taskwarrior location)))

(defn taskrc-file
  [app-config]
  (if-let [taskrc (get-in app-config [:tracker :taskrc])]
    (java.io.File. taskrc)
    (java.io.File. (or (System/getenv "HOME") (System/getProperty "user.home")) ".taskrc")))

(defn missing-taskrc?
  [ex]
  (str/includes? (or (:err (ex-data ex)) "")
                 "Cannot proceed without rc file."))

(defn normalize-project
  [scope name]
  {:ref (domain/identity :taskwarrior :project name)
   :display-id name
   :title name
   :scopes [scope]})

(defn normalize-tag
  [scope name]
  {:ref (domain/identity :taskwarrior :label name)
   :display-id name
   :scopes [scope]})

(defn managed-annotation?
  [annotation]
  (str/starts-with? (or (:description annotation) "") annotation-prefix))

(defn urgent-priority? [task]
  (some #(= urgent-priority-prefix (:description %)) (:annotations task)))

(defn waiting-state-marker? [annotation]
  (= waiting-state-marker (:description annotation)))

(defn normalize-state [task]
  (case (:status task)
    "completed" "completed"
    "deleted" "canceled"
    "waiting" "waiting"
    "pending" (cond (:start task) "active"
                    (some waiting-state-marker? (:annotations task)) "waiting"
                    :else "open")
    "recurring" "open"
    "open"))

(defn normalize-priority [task]
  (if (urgent-priority? task)
    "urgent"
    (case (:priority task)
      "H" "high"
      "M" "medium"
      "L" "low"
      "none")))

(defn normalize-date [value]
  (when value
    (-> (java.time.LocalDateTime/parse value task-date-formatter)
        (.toInstant java.time.ZoneOffset/UTC)
        str)))

(defn dependency-ids [task]
  (let [depends (:depends task)]
    (cond
      (nil? depends) []
      (sequential? depends) (mapv str depends)
      :else (str/split (str depends) #","))))

(declare short-uuid)

(defn blocker-summary [scope task-index uuid]
  (let [task (get task-index uuid)]
    {:ref (domain/identity :taskwarrior :tracker-item uuid)
     :display-id (short-uuid uuid)
     :title (:description task)
     :scopes [scope]}))

(defn tracker-description
  [task]
  (or (when-let [annotation (first (filter managed-annotation? (:annotations task)))]
        (subs (:description annotation) (count annotation-prefix)))
      (:details task)
      ""))

(defn short-uuid
  [uuid]
  (subs uuid 0 (min 8 (count uuid))))

(defn normalize-task
  ([scope task]
   (normalize-task scope task {}))
  ([scope task task-index]
   (when task
     (let [uuid (str (:uuid task))]
       {:ref (domain/identity :taskwarrior :tracker-item uuid)
        :display-id (short-uuid uuid)
        :uuid uuid
        :number (:id task)
        :title (:description task)
        :description (tracker-description task)
        :state (normalize-state task)
        :priority (normalize-priority task)
        :due-at (normalize-date (:due task))
        :available-at (normalize-date (:wait task))
        :blocked-by (mapv #(blocker-summary scope task-index %) (dependency-ids task))
        :scopes [scope]
        :project (when-let [project (:project task)]
                   (normalize-project scope project))
        :labels (mapv #(normalize-tag scope %) (or (:tags task) []))}))))

(defn tasks
  [app-config scope]
  (let [native-tasks (export-tasks app-config)
        task-index (into {} (map (juxt (comp str :uuid) identity)) native-tasks)]
    (mapv #(normalize-task scope % task-index) native-tasks)))

(defn task-reference?
  [reference]
  (boolean (re-matches #"(?i)(?:\d+|[0-9a-f]{8,36}(?:-[0-9a-f]{4}){0,3}-?[0-9a-f]{0,12})"
                       reference)))

(defn task-matches?
  [task reference]
  (let [needle (str/lower-case reference)]
    (some #(str/includes? (str/lower-case (str %)) needle)
          [(:uuid task) (:number task) (:title task) (:description task)])))

(defn resolve-item
  [app-config scope reference]
  (let [reference (str/trim (or reference ""))
        native-matches (when (task-reference? reference)
                         (export-tasks app-config reference))
        _ (when (> (count native-matches) 1)
            (throw (ex-info (str "Taskwarrior item reference is ambiguous: " reference)
                            {:code :ambiguous-item
                             :reference reference
                             :matches (mapv #(short-uuid (str (:uuid %))) native-matches)})))
        native-direct (first native-matches)
        direct (when native-direct
                 (let [task-index (when (seq (dependency-ids native-direct))
                                    (into {} (map (juxt (comp str :uuid) identity))
                                          (export-tasks app-config)))]
                   (normalize-task scope native-direct task-index)))]
    (or direct
        (let [matches (filter #(task-matches? % reference) (tasks app-config scope))]
          (case (count matches)
            0 nil
            1 (first matches)
            (throw (ex-info (str "Taskwarrior item reference is ambiguous: " reference)
                            {:code :ambiguous-item
                             :reference reference
                             :matches (mapv :display-id matches)})))))))

(defn helper-values
  [app-config & args]
  (->> (str/split-lines (apply task-run app-config args))
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn resolve-name
  [entities reference kind]
  (let [reference (str/trim (or reference ""))
        exact (filter #(= reference (:display-id %)) entities)]
    (or (first exact)
        (let [matches (filter #(= (str/lower-case reference)
                                  (str/lower-case (:display-id %)))
                              entities)]
          (case (count matches)
            0 nil
            1 (first matches)
            (throw (ex-info (str "Taskwarrior " (name kind)
                                 " reference is ambiguous: " reference)
                            {:code (keyword (str "ambiguous-" (name kind)))
                             :kind kind
                             :reference reference
                             :matches (mapv :display-id matches)})))))))

(defn projects
  [app-config scope]
  (mapv #(normalize-project scope %)
        (helper-values app-config "_unique" "project")))

(defn resolve-project
  [app-config scope reference]
  (let [reference (str/trim (or reference ""))]
    (when-not (str/blank? reference)
      (or (resolve-name (projects app-config scope) reference :project)
          (normalize-project scope reference)))))

(defn tags
  [app-config scope]
  (->> (export-tasks app-config)
       (mapcat #(or (:tags %) []))
       distinct
       (mapv #(normalize-tag scope %))))

(defn resolve-labels
  [app-config scope label-refs _configured-scope]
  (let [available (when (seq label-refs) (tags app-config scope))]
    (mapv (fn [reference]
            (let [reference (str/trim (or reference ""))]
              (or (resolve-name available reference :label)
                  (when-not (str/blank? reference) (normalize-tag scope reference))
                  (throw (ex-info "Taskwarrior tag must not be blank."
                                  {:code :label-not-found :label reference})))))
          (distinct label-refs))))

(defn entity-name
  [entity]
  (or (get-in entity [:ref :id]) (:display-id entity)))

(defn timestamp
  []
  (.format task-date-formatter
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn upsert-description
  [task description]
  (let [annotations (vec (or (:annotations task) []))
        managed (filter managed-annotation? annotations)]
    (when (> (count managed) 1)
      (throw (ex-info "The Taskwarrior task has duplicate ttt description annotations."
                      {:code :malformed-managed-section})))
    (when (and (seq managed) (some? (:details task))
               (not= (:details task) (tracker-description (dissoc task :details))))
      (throw (ex-info "Taskwarrior details conflict with the legacy ttt description; resolve the conflict before updating."
                      {:code :malformed-managed-section})))
    (cond-> (assoc task :annotations (vec (remove managed-annotation? annotations)))
      (some? description) (assoc :details description)
      (nil? description) (dissoc :details))))

(defn native-date [value]
  (when value
    (.format task-date-formatter
             (.atZone (java.time.Instant/parse value) java.time.ZoneOffset/UTC))))

(defn set-native-date [task field value]
  (if value
    (assoc task field (native-date value))
    (dissoc task field)))

(defn apply-state [task state]
  (let [task (update task :annotations
                     #(vec (remove waiting-state-marker? (or % []))))]
    (case state
      "open" (-> task (assoc :status "pending") (dissoc :start :end))
      "active" (-> task (assoc :status "pending" :start (or (:start task) (timestamp))) (dissoc :end))
      "waiting" (-> task
                    (assoc :status "pending")
                    (dissoc :start :end)
                    (update :annotations conj {:entry (timestamp) :description waiting-state-marker}))
      "completed" (-> task (assoc :status "completed" :end (or (:end task) (timestamp))) (dissoc :start))
      "canceled" (-> task (assoc :status "deleted" :end (or (:end task) (timestamp))) (dissoc :start)))))

(defn set-priority [task priority]
  (let [task (update task :annotations
                     #(vec (remove (fn [annotation]
                                     (= urgent-priority-prefix (:description annotation)))
                                   (or % []))))]
    (case priority
      "urgent" (-> task
                   (assoc :priority "H")
                   (update :annotations conj {:entry (timestamp) :description urgent-priority-prefix}))
      "high" (assoc task :priority "H")
      "medium" (assoc task :priority "M")
      "low" (assoc task :priority "L")
      (dissoc task :priority))))

(defn set-dependencies [task blockers]
  (if (seq blockers)
    (assoc task :depends (str/join "," (map entity-name blockers)))
    (dissoc task :depends)))

(defn apply-work-item-intent [task intent]
  (cond-> task
    (contains? intent :state) (apply-state (:state intent))
    (contains? intent :priority) (set-priority (:priority intent))
    (contains? intent :due-at) (set-native-date :due (:due-at intent))
    (contains? intent :available-at) (set-native-date :wait (:available-at intent))
    (contains? intent :blocked-by) (set-dependencies (:blocked-by intent))))

(defn normalize-result [scope task previous intent]
  (cond-> (normalize-task scope task)
    (contains? intent :blocked-by) (assoc :blocked-by (:blocked-by intent))
    (and previous (not (contains? intent :blocked-by))) (assoc :blocked-by (:blocked-by previous))))

(defn import-task!
  [app-config task]
  (task-run-input app-config
                  (json/generate-string [task])
                  "import" "-" "rc.confirmation=off"))

(defn create-item-from-intent!
  [app-config scope context intent]
  (when (:parent context)
    (throw (ex-info "Taskwarrior does not support parent tasks; use --project instead."
                    {:code :unsupported-parent})))
  (let [{:keys [title description labels]} intent
        uuid (str (java.util.UUID/randomUUID))
        task (-> (cond-> {:uuid uuid
                          :description title
                          :status "pending"
                          :entry (timestamp)}
                   (not (str/blank? description))
                   (assoc :details description)
                   (:project context) (assoc :project (entity-name (:project context)))
                   (seq labels) (assoc :tags (mapv entity-name labels)))
                 (apply-work-item-intent intent))]
    (import-task! app-config task)
    (some-> (first (export-tasks app-config uuid))
            (#(normalize-result scope % nil intent)))))


(defn merge-intended-tags [task item labels]
  (let [previous (set (map entity-name (:labels item)))
        desired (mapv entity-name labels)
        desired-set (set desired)
        added (remove previous desired)
        removed (set (remove desired-set previous))]
    (assoc task :tags (->> (concat (remove removed (or (:tags task) [])) added)
                           distinct
                           vec))))

(defn update-item-from-intent!
  [app-config scope item intent]
  (let [{:keys [description labels]} intent
        uuid (get-in item [:ref :id])
        task (or (first (export-tasks app-config uuid))
                 (throw (ex-info (str "Taskwarrior task not found: " uuid)
                                 {:code :tracker-item-not-found :item-ref uuid})))
        description-changed? (or (not= description (:description item))
                                 (some managed-annotation? (:annotations task)))
        labels-changed? (not= (set (map entity-name (:labels item)))
                              (set (map entity-name labels)))
        updated (cond-> task
                  description-changed? (upsert-description
                                        (if (not= description (:description item))
                                          description
                                          (tracker-description task)))
                  labels-changed? (merge-intended-tags item labels)
                  true (apply-work-item-intent intent))]
    (import-task! app-config updated)
    (some-> (first (export-tasks app-config uuid))
            (#(normalize-result scope % item intent)))))

(defn comment-item!
  [app-config item body]
  (task-run app-config (get-in item [:ref :id]) "annotate" body)
  nil)

(defn unsupported-parent!
  [& _]
  (throw (ex-info "Taskwarrior does not support parent tasks; use --project instead."
                  {:code :unsupported-parent})))

(defn assert-ready!
  [app-config]
  (try
    (task-version)
    (configured-scope app-config)
    nil
    (catch Exception ex
      (if (missing-taskrc? ex)
        (throw (ex-info "Taskwarrior has no configuration file. Run `ttt setup` to create an empty one automatically."
                        {:code :provider-config-invalid
                         :provider :taskwarrior
                         :reason :taskrc-missing}
                        ex))
        (throw (ex-info "Taskwarrior tracker requires a working `task` CLI and configuration."
                        {:code :provider-config-invalid :provider :taskwarrior}
                        ex))))))

(defn setup
  [app-config]
  (try
    (assert-ready! app-config)
    (catch Exception ex
      (if (= :taskrc-missing (:reason (ex-data ex)))
        (let [taskrc (taskrc-file app-config)]
          (some-> taskrc .getParentFile .mkdirs)
          (.createNewFile taskrc)
          (assert-ready! app-config))
        (throw ex))))
  (println (str "Taskwarrior tracker: " (task-version)
                ", data " (get-in (configured-scope app-config) [:id])))
  nil)

(def capabilities
  #{:configured-scope
    :list-items
    :search-parent-items
    :resolve-parent-item
    :resolve-item
    :search-projects
    :resolve-project
    :search-labels
    :resolve-labels
    :create-item!
    :comment-item!
    :update-item!})

(defn neutral-adapter
  [app-config]
  (let [scope* (delay (configured-scope app-config))]
    {:provider :taskwarrior
     :capabilities capabilities
     :item-capabilities #{:item-lifecycle
                          :item-priority
                          :item-due-dates
                          :item-availability
                          :item-blockers}
     :configured-scope (fn [] @scope*)
     :list-items (fn [matches? limit]
                   (->> (tasks app-config @scope*) (filter matches?) (take limit) vec))
     :search-parent-items #(tasks app-config @scope*)
     :resolve-parent-item unsupported-parent!
     :resolve-item #(resolve-item app-config @scope* %)
     :search-projects #(projects app-config @scope*)
     :resolve-project #(resolve-project app-config @scope* %)
     :search-labels #(tags app-config @scope*)
     :resolve-labels #(resolve-labels app-config @scope* %1 %2)
     :create-item! #(create-item-from-intent! app-config @scope* %1 %2)
     :comment-item! #(comment-item! app-config %1 %2)
     :update-item! #(update-item-from-intent! app-config @scope* %1 %2)}))
