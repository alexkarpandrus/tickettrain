(ns ttt.providers.tracker.logseq-db
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]
            [ttt.providers.tracker.logseq :as logseq]))

(def native-states {"open" "todo" "active" "doing" "waiting" "in-review"
                    "completed" "done" "canceled" "canceled"})
(def status-states {"logseq.property/status.backlog" "open"
                    "logseq.property/status.todo" "open"
                    "logseq.property/status.doing" "active"
                    "logseq.property/status.in-review" "waiting"
                    "logseq.property/status.done" "completed"
                    "logseq.property/status.canceled" "canceled"})
(def page-size 200)
(def pull-query
  "[:find [(pull ?id [:db/id :block/uuid :block/title {:block/page [:db/id :block/uuid :block/title]} {:logseq.property/status [:db/ident]}]) ...] :in $ [?id ...]]")

(defn assert-ready! [app-config]
  (let [settings (:tracker app-config)]
    (config/assert-settings! :logseq-db settings [:graph])
    (when-not (and (string? (:graph settings))
                   (or (nil? (:root-dir settings))
                       (and (string? (:root-dir settings)) (not (str/blank? (:root-dir settings))))))
      (throw (ex-info "Logseq DB graph and CLI root must be nonblank strings."
                      {:code :provider-config-invalid :provider :logseq-db})))))

(defn root-dir [app-config]
  (str (.getCanonicalPath
        (java.io.File. (or (get-in app-config [:tracker :root-dir])
                          (str (System/getProperty "user.home") "/logseq"))))))

(defn request! [app-config & args]
  (let [output (try
                 (apply shell/run "logseq" (concat args
                        [(str "--root-dir=" (root-dir app-config))
                         (str "--graph=" (get-in app-config [:tracker :graph]))
                         "--output=json"]))
                 (catch Exception _
                   ;; Subprocess errors include argv; task content must not leak through errors.
                   (throw (ex-info "Logseq CLI request failed. Check the selected graph and CLI installation."
                                   {:code :logseq-cli-failed :provider :logseq-db}))))
        response (try (json/parse-string output true)
                      (catch Exception _
                        (throw (ex-info "Logseq CLI returned invalid JSON."
                                        {:code :invalid-logseq-response :provider :logseq-db}))))]
    (when-not (= "ok" (:status response))
      (throw (ex-info "Logseq CLI did not confirm the request. Inspect the graph before retrying a write."
                      {:code :logseq-cli-failed :provider :logseq-db})))
    (:data response)))

(defn graph-scope [app-config]
  (let [info (request! app-config "graph" "info")
        uuid (get-in info [:kv :logseq.kv/local-graph-uuid])]
    (when-not (and (= "db" (get-in info [:kv :logseq.kv/db-type]))
                   (logseq/block-uuid? uuid))
      (throw (ex-info "Select a native Logseq DB graph with a stable graph UUID."
                      {:code :invalid-logseq-response :provider :logseq-db})))
    (domain/scope-identity :logseq-db uuid)))

(defn assert-scope! [app-config scope]
  (when-not (= scope (graph-scope app-config))
    (throw (ex-info "The configured Logseq DB graph changed. Preview again before applying."
                    {:code :tracker-scope-mismatch :provider :logseq-db})))
  scope)

(defn native! [app-config scope & args]
  (assert-scope! app-config scope)
  (apply request! app-config args))

(defn normalize-page [scope page]
  (when-not (and (logseq/block-uuid? (:block/uuid page)) (string? (:block/title page)))
    (throw (ex-info "Logseq CLI returned a page without its UUID or title."
                    {:code :invalid-logseq-response :provider :logseq-db})))
  {:ref (domain/contained-identity :logseq-db :project (:id scope) (:block/uuid page))
   :display-id (:block/uuid page) :title (:block/title page) :scopes [scope]
   :logseq/db-id (:db/id page)})

(defn normalize-task [scope task]
  (let [content (:block/title task)
        status (get-in task [:logseq.property/status :db/ident])
        state (get status-states status)]
    (when-not (and (logseq/block-uuid? (:block/uuid task)) (string? content) (or (nil? status) state))
      (throw (ex-info "Logseq CLI returned an unsupported task status, UUID, or content."
                      {:code :invalid-logseq-response :provider :logseq-db})))
    (let [[title description] (str/split content #"\r?\n" 2)]
      (cond-> {:ref (domain/contained-identity :logseq-db :tracker-item (:id scope) (:block/uuid task))
               :display-id (:block/uuid task) :title title :description (or description "")
               :state state :scopes [scope] :labels []
               :logseq/content content :logseq/status status :logseq/db-id (:db/id task)}
        (:block/page task) (assoc :project (normalize-page scope (:block/page task)))))))

(def task-class-rules
  '[[(task-class ?tag) [?tag :db/ident :logseq.class/Task]]
    [(task-class ?tag) [?tag :logseq.property.class/extends ?parent] (task-class ?parent)]])

(def task-ids-query
  "[:find [?id ...] :in $ % ?after :where [?id :block/tags ?tag] (task-class ?tag) [(> ?id ?after)]]")

(defn list-nodes [app-config scope kind normalize matches? limit]
  ;; The CLI task list omits inherited classes and empty tasks. Discover their
  ;; native IDs once, then hydrate bounded batches without copying other notes.
  (let [task-ids (when (= kind "task")
                   (let [ids (:result (native! app-config scope "query" "--query" task-ids-query
                                              "--inputs" (pr-str [task-class-rules 0])))]
                     (when-not (and (sequential? ids) (every? integer? ids))
                       (throw (ex-info "Logseq CLI returned invalid entity IDs."
                                       {:code :invalid-logseq-response :provider :logseq-db})))
                     (vec (sort ids))))]
    (loop [offset 0 result []]
      (if (>= (count result) limit)
        result
        (let [ids (if (= kind "task")
                    (subvec task-ids offset (min (count task-ids) (+ offset page-size)))
                    (let [items (:items (native! app-config scope "list" kind "--fields=id"
                                                 (str "--limit=" page-size) (str "--offset=" offset)))]
                      (when-not (sequential? items)
                        (throw (ex-info "Logseq CLI returned invalid entity IDs."
                                        {:code :invalid-logseq-response :provider :logseq-db})))
                      (mapv :db/id items)))
              _ (when-not (every? integer? ids)
                  (throw (ex-info "Logseq CLI returned invalid entity IDs."
                                  {:code :invalid-logseq-response :provider :logseq-db})))
              entities (when (seq ids)
                         (:result (native! app-config scope "query" "--query" pull-query
                                           "--inputs" (pr-str [ids]))))
              _ (when-not (or (empty? ids) (and (sequential? entities) (= (count ids) (count entities))))
                  (throw (ex-info "Logseq CLI did not return the selected entities."
                                  {:code :invalid-logseq-response :provider :logseq-db})))
              result (into result (comp (map #(normalize scope %)) (filter matches?)
                                        (take (- limit (count result)))) entities)]
          (if (empty? ids) result (recur (+ offset (count ids)) result)))))))

(defn projects [app-config scope]
  (list-nodes app-config scope "page" normalize-page (constantly true) Long/MAX_VALUE))

(defn reference-id [scope kind reference]
  (let [identity (when (str/starts-with? reference "logseq-db:") (domain/key-identity reference))]
    (when (and identity
               (or (not= :logseq-db (:provider identity)) (not= kind (:kind identity))
                   (not= (:id scope) (:container identity))))
      (throw (ex-info "The reference is outside the configured Logseq DB graph."
                      {:code :tracker-scope-mismatch :provider :logseq-db})))
    (if identity (:id identity) reference)))


(def page-query
  (str/replace pull-query ":in $ [?id ...]]"
               ":in $ ?uuid ?title :where [?id :block/name] [?id :block/tags ?class] [?class :db/ident :logseq.class/Page] (or [?id :block/uuid ?uuid] [?id :block/title ?title])]"))

(def uuid-page-query
  (str/replace pull-query ":in $ [?id ...]]"
               ":in $ ?uuid :where [?id :block/uuid ?uuid] [?id :block/name] [?id :block/tags ?class] [?class :db/ident :logseq.class/Page]]"))
(defn resolve-project [app-config scope reference]
  (let [id (reference-id scope :project reference)
        matches (map #(normalize-page scope %) (:result (native! app-config scope "query" "--query" page-query
                                                                  "--inputs" (pr-str [(when (logseq/block-uuid? id) (java.util.UUID/fromString id)) id]))))]
    (when (> (count matches) 1)
      (throw (ex-info "The Logseq DB page reference is ambiguous. Use its UUID."
                      {:code :ambiguous-project :provider :logseq-db})))
    (first matches)))


(def uuid-task-query
  (str/replace pull-query ":in $ [?id ...]]"
               ":in $ % ?uuid :where [?id :block/uuid ?uuid] [?id :block/tags ?tag] (task-class ?tag)]"))

(defn resolve-item [app-config scope reference]
  (let [id (reference-id scope :tracker-item reference)]
    (when (logseq/block-uuid? id)
      (when-let [task (first (:result (native! app-config scope "query" "--query" uuid-task-query
                                              "--inputs" (pr-str [task-class-rules (java.util.UUID/fromString id)]))))]
        (normalize-task scope task)))))

(defn unsupported! [concept]
  (throw (ex-info (str "The Logseq DB tracker does not support " (name concept) ".")
                  {:code :unsupported-capability :provider :logseq-db :capability concept})))

(defn validate-intent! [action context intent]
  (when (and (#{:create-item :create-new} action) (nil? (:project context)))
    (throw (ex-info "Select a native Logseq DB page with project before creating a task."
                    {:code :invalid-context :provider :logseq-db})))
  (when (and (contains? intent :title)
             (or (str/blank? (:title intent)) (re-find #"[\r\n]" (:title intent))))
    (throw (ex-info "Logseq DB task titles must be nonblank single-line strings."
                    {:code :unsupported-work-item-value :provider :logseq-db :field :title})))
  (when (and (contains? intent :state) (not (contains? native-states (:state intent))))
    (throw (ex-info "Logseq DB does not support the requested state."
                    {:code :unsupported-work-item-value :provider :logseq-db :field :state})))
  intent)

(defn current-item! [app-config scope item]
  (let [current (or (resolve-item app-config scope (get-in item [:ref :id]))
                    (throw (ex-info "The Logseq DB task no longer exists."
                                    {:code :tracker-item-not-found :provider :logseq-db})))]
    (when-not (= (select-keys item [:logseq/content :logseq/status])
                 (select-keys current [:logseq/content :logseq/status]))
      (throw (ex-info "The Logseq DB task changed. Preview again before applying."
                      {:code :stale-proposal :provider :logseq-db})))
    current))

(defn content [intent]
  (str (:title intent) (when (seq (:description intent)) (str "\n" (:description intent)))))

(defn create-item! [app-config scope context intent]
  (validate-intent! :create-item context intent)
  (when (:parent context) (unsupported! :parents))
  (when (seq (:labels intent)) (unsupported! :labels))
  (let [ref (get-in context [:project :ref])
        row (first (:result (native! app-config scope "query" "--query" uuid-page-query
                                    "--inputs" (pr-str [(java.util.UUID/fromString (:id ref))]))))
        page (when row (normalize-page scope row))]
    (when-not (= ref (:ref page))
      (throw (ex-info "The selected Logseq DB page no longer exists."
                      {:code :stale-proposal :provider :logseq-db})))
    (try
      (let [ids (:result (native! app-config scope "upsert" "block" (str "--target-uuid=" (:display-id page))
                                  ;; last-child reuses an existing empty tail block in the native SDK.
                                  "--pos=first-child" "--update-tags=[\"Task\"]"
                                  (str "--blocks=" (pr-str [{:block/title (content intent)}]))))]
        (when-not (and (= 1 (count ids)) (integer? (first ids)))
          (throw (ex-info "The created task did not return one native entity ID." {})))
        (let [rows (:result (native! app-config scope "query" "--query" pull-query
                                    "--inputs" (pr-str [ids])))
              _ (when-not (= 1 (count rows))
                  (throw (ex-info "The created task could not be read." {})))
              uuid (:block/uuid (first rows))
              _ (when-not (and (logseq/block-uuid? uuid)
                               (= (content intent) (:block/title (first rows)))
                               (= (:display-id page) (get-in (first rows) [:block/page :block/uuid]))
                               (nil? (:logseq.property/status (first rows))))
                  (throw (ex-info "The created block no longer matches the approved content, page, or initial status." {})))
              _ (native! app-config scope "upsert" "task" (str "--uuid=" uuid)
                         (str "--status=" (get native-states (:state intent "open"))))
              created (normalize-task scope
                        (first (:result (native! app-config scope "query" "--query" pull-query
                                                 "--inputs" (pr-str [ids])))))]
          (when-not (and (= (content intent) (:logseq/content created))
                         (= (:state intent "open") (:state created))
                         (= (:ref page) (get-in created [:project :ref])))
            (throw (ex-info "The returned task does not match the approved creation." {})))
          created))
      (catch Exception _
        (throw (ex-info "Inspect the approved Logseq DB page before retrying create_item; creation may have succeeded."
                        {:code :logseq-create-outcome-unknown :provider :logseq-db
                         :page (get-in page [:ref :id])}))))))

(defn update-item! [app-config scope item intent]
  (validate-intent! :update-item item intent)
  (when (seq (:labels intent)) (unsupported! :labels))
  (let [current (current-item! app-config scope item)
        text (if (some (fn [field] (and (contains? intent field)
                                       (not= (get intent field) (get current field))))
                       [:title :description])
               (content (merge (select-keys current [:title :description]) intent))
               (:logseq/content current))
        state-changed? (and (contains? intent :state) (not= (:state intent) (:state current)))]
    (when (not= text (:logseq/content current))
      (native! app-config scope "upsert" "block" (str "--uuid=" (:display-id current))
               (str "--content=" text)))
    (when state-changed?
      (current-item! app-config scope (assoc current :logseq/content text))
      (native! app-config scope "upsert" "task" (str "--uuid=" (:display-id current))
               (str "--status=" (get native-states (:state intent)))))
    (or (resolve-item app-config scope (:display-id current))
        (throw (ex-info "Inspect the Logseq DB task before retrying; its update could not be read."
                        {:code :invalid-logseq-response :provider :logseq-db})))))

(defn comment-item! [app-config scope item body]
  (current-item! app-config scope item)
  (native! app-config scope "upsert" "block" (str "--target-uuid=" (:display-id item))
           "--pos=first-child" (str "--blocks=" (pr-str [{:block/title body}])))
  nil)

(defn setup [app-config]
  (assert-ready! app-config)
  (graph-scope app-config)
  (println "Logseq DB tracker: native CLI graph is available"))

(defn neutral-adapter [app-config]
  (let [scope (graph-scope app-config)]
    {:provider :logseq-db :capabilities (get adapters/required-capabilities :tracker)
     :item-capabilities #{:item-titles :item-descriptions :item-lifecycle}
     :configured-scope #(assert-scope! app-config scope)
     :list-items #(list-nodes app-config scope "task" normalize-task %1 %2)
     :search-parent-items #(list-nodes app-config scope "task" normalize-task (constantly true) Long/MAX_VALUE)
     :resolve-item #(resolve-item app-config scope %)
     :resolve-parent-item (fn [_] (unsupported! :parents))
     :search-projects #(projects app-config scope)
     :resolve-project #(resolve-project app-config scope %)
     :search-labels (constantly [])
     :resolve-labels (fn [references _] (when (seq references) (unsupported! :labels)) [])
     :validate-work-item-intent! validate-intent!
     :create-item! #(create-item! app-config scope %1 %2)
     :update-item! #(update-item! app-config scope %1 %2)
     :comment-item! #(comment-item! app-config scope %1 %2)}))
