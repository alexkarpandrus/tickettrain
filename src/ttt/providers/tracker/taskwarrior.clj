(ns ttt.providers.tracker.taskwarrior
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]))

(def annotation-prefix "<!-- ttt:description -->\n")

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

(defn tracker-description
  [task]
  (if-let [annotation (first (filter managed-annotation? (:annotations task)))]
    (subs (:description annotation) (count annotation-prefix))
    ""))

(defn short-uuid
  [uuid]
  (subs uuid 0 (min 8 (count uuid))))

(defn normalize-task
  [scope task]
  (when task
    (let [uuid (str (:uuid task))]
      {:ref (domain/identity :taskwarrior :tracker-item uuid)
       :display-id (short-uuid uuid)
       :uuid uuid
       :number (:id task)
       :title (:description task)
       :description (tracker-description task)
       :state {:name (:status task)}
       :scopes [scope]
       :project (when-let [project (:project task)]
                  (normalize-project scope project))
       :labels (mapv #(normalize-tag scope %) (or (:tags task) []))})))

(defn tasks
  [app-config scope]
  (mapv #(normalize-task scope %) (export-tasks app-config)))

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
  (let [reference (str/trim (or reference ""))]
    (or (when (task-reference? reference)
          (some-> (first (export-tasks app-config reference))
                  (#(normalize-task scope %))))
        (first (filter #(task-matches? % reference) (tasks app-config scope))))))

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
  (resolve-name (projects app-config scope) reference :project))

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
            (or (resolve-name available reference :label)
                (throw (ex-info (str "Taskwarrior tag not found: " reference)
                                {:code :label-not-found :label reference}))))
          (distinct label-refs))))

(defn entity-name
  [entity]
  (or (get-in entity [:ref :id]) (:display-id entity)))

(defn timestamp
  []
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'" java.util.Locale/ROOT)
           (java.time.ZonedDateTime/now java.time.ZoneOffset/UTC)))

(defn description-annotation
  [description]
  {:entry (timestamp) :description (str annotation-prefix description)})

(defn upsert-description
  [task description]
  (let [annotations (vec (or (:annotations task) []))
        managed (filter managed-annotation? annotations)]
    (when (> (count managed) 1)
      (throw (ex-info "The Taskwarrior task has duplicate ttt description annotations."
                      {:code :malformed-managed-section})))
    (assoc task :annotations
           (if (seq managed)
             (mapv #(if (managed-annotation? %)
                      (assoc % :description (str annotation-prefix description))
                      %)
                   annotations)
             (conj annotations (description-annotation description))))))

(defn import-task!
  [app-config task]
  (task-run-input app-config
                  (json/generate-string [task])
                  "import" "-" "rc.confirmation=off"))

(defn create-item-from-intent!
  [app-config scope context {:keys [title description labels]}]
  (when (:parent context)
    (throw (ex-info "Taskwarrior does not support parent tasks; use --project instead."
                    {:code :unsupported-parent})))
  (let [uuid (str (java.util.UUID/randomUUID))
        task (cond-> {:uuid uuid
                      :description title
                      :status "pending"
                      :entry (timestamp)
                      :annotations [(description-annotation description)]}
               (:project context) (assoc :project (entity-name (:project context)))
               (seq labels) (assoc :tags (mapv entity-name labels)))]
    (import-task! app-config task)
    (some-> (first (export-tasks app-config uuid))
            (#(normalize-task scope %)))))

(defn update-item-from-intent!
  [app-config scope item {:keys [description labels]}]
  (let [uuid (get-in item [:ref :id])
        task (or (first (export-tasks app-config uuid))
                 (throw (ex-info (str "Taskwarrior task not found: " uuid)
                                 {:code :tracker-item-not-found :item-ref uuid})))
        updated (-> task
                    (upsert-description description)
                    (assoc :tags (mapv entity-name labels)))]
    (import-task! app-config updated)
    (some-> (first (export-tasks app-config uuid))
            (#(normalize-task scope %)))))

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
      (throw (ex-info "Taskwarrior tracker requires a working `task` CLI and configuration."
                      {:code :provider-config-invalid :provider :taskwarrior}
                      ex)))))

(defn setup
  [app-config]
  (assert-ready! app-config)
  (println (str "Taskwarrior tracker: " (task-version)
                ", data " (get-in (configured-scope app-config) [:id])))
  nil)

(def capabilities
  #{:configured-scope
    :search-parent-items
    :resolve-parent-item
    :resolve-item
    :search-projects
    :resolve-project
    :search-labels
    :resolve-labels
    :create-item!
    :update-item!})

(defn neutral-adapter
  [app-config]
  (let [scope* (delay (configured-scope app-config))]
    {:provider :taskwarrior
     :capabilities capabilities
     :configured-scope (fn [] @scope*)
     :search-parent-items #(tasks app-config @scope*)
     :resolve-parent-item unsupported-parent!
     :resolve-item #(resolve-item app-config @scope* %)
     :search-projects #(projects app-config @scope*)
     :resolve-project #(resolve-project app-config @scope* %)
     :search-labels #(tags app-config @scope*)
     :resolve-labels #(resolve-labels app-config @scope* %1 %2)
     :create-item! #(create-item-from-intent! app-config @scope* %1 %2)
     :update-item! #(update-item-from-intent! app-config @scope* %1 %2)}))
