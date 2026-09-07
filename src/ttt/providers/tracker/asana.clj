(ns ttt.providers.tracker.asana
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.config :as config]
            [ttt.domain :as domain]))

(def api-path "/api/1.0")

(def task-fields "name,notes,permalink_url,completed,projects,projects.name,tags,tags.name,parent,parent.name")

(defn base-url
  [app-config]
  (str/replace (or (get-in app-config [:tracker :base-url]) "https://app.asana.com") #"/+$" ""))

(defn token
  [app-config]
  (or (get-in app-config [:tracker :token]) ""))

(defn api-endpoint
  [app-config path]
  (str (base-url app-config) api-path path))

(defn api!
  [app-config method path query]
  (let [url (api-endpoint app-config path)
        headers {"Authorization" (str "Bearer " (token app-config))}
        response (case method
                   :get (http/get url {:headers headers :query-params query :throw false})
                   :post (http/post url {:headers headers
                                         :body (json/generate-string query)
                                         :content-type :json
                                         :throw false})
                   :put (http/put url {:headers headers
                                       :body (json/generate-string query)
                                       :content-type :json
                                       :throw false}))
        status (:status response)
        body (try (json/parse-string (:body response) true) (catch Exception _ nil))]
    (when (>= status 400)
      (throw (ex-info (str "Asana API request failed with status " status ".")
                      {:status status :body body})))
    body))

(defn workspace-gid
  [app-config]
  (or (get-in app-config [:tracker :workspace])
      (get-in (api! app-config :get "/workspaces" nil) [:data 0 :gid])))

(defn site-scope
  [app-config]
  (domain/scope-identity :asana (workspace-gid app-config)))

(defn provider-id
  [entity]
  (if (map? entity) (or (get-in entity [:ref :id]) (:id entity)) entity))

(defn normalize-project
  [scope project]
  {:ref (domain/identity :asana :project (:gid project))
   :display-id (:name project)
   :title (:name project)
   :scopes [scope]})

(defn normalize-tag
  [scope tag]
  {:ref (domain/identity :asana :label (:gid tag))
   :display-id (:name tag)
   :scopes [scope]})

(defn normalize-parent
  [scope parent]
  (when parent
    {:ref (domain/identity :asana :tracker-item (:gid parent))
     :display-id (:gid parent)
     :title (:name parent)
     :scopes [scope]}))

(defn normalize-task
  [scope task]
  (when task
    {:ref (domain/identity :asana :tracker-item (:gid task))
     :display-id (:gid task)
     :title (:name task)
     :description (or (:notes task) "")
     :url (:permalink_url task)
     :state {:name (if (:completed task) "completed" "incomplete")}
     :scopes [scope]
     :project (when-let [p (first (:projects task))] (normalize-project scope p))
     :parent (when-let [p (:parent task)] (normalize-parent scope p))
     :labels (mapv #(normalize-tag scope %) (or (:tags task) []))}))

(defn task-by-gid
  [app-config gid]
  (let [scope (site-scope app-config)]
    (try
      (normalize-task scope (get (api! app-config :get (str "/tasks/" gid)
                                       {:opt_fields task-fields})
                                 :data))
      (catch Exception _ nil))))

(defn tasks
  [app-config]
  (let [scope (site-scope app-config)
        gid (workspace-gid app-config)
        response (api! app-config :get "/tasks"
                       {:workspace gid :assignee "me" :limit 100 :opt_fields task-fields})]
    (mapv #(normalize-task scope %) (:data response))))

(defn search-tasks
  [app-config text]
  (let [scope (site-scope app-config)
        gid (workspace-gid app-config)]
    (try
      (let [response (api! app-config :get (str "/workspaces/" gid "/tasks/search")
                           {:text text :limit 5 :opt_fields task-fields})]
        (mapv #(normalize-task scope %) (:data response)))
      (catch Exception ex
        (if (= 402 (:status (ex-data ex)))
          ;; ponytail: free-plan fallback sees assigned tasks only; add project aggregation if unassigned search is needed.
          (let [query (str/lower-case text)]
            (->> (tasks app-config)
                 (filter #(str/includes? (str/lower-case (str (:title %) "\n" (:description %))) query))
                 (take 5)
                 vec))
          (throw ex))))))

(defn resolve-item
  [app-config ref]
  (let [ref (str/trim (or ref ""))]
    (if (re-matches #"^\d+$" ref)
      (task-by-gid app-config ref)
      (first (search-tasks app-config ref)))))

(defn projects
  [app-config]
  (let [scope (site-scope app-config)
        gid (workspace-gid app-config)
        response (api! app-config :get (str "/workspaces/" gid "/projects")
                       {:limit 100 :opt_fields "name"})]
    (mapv #(normalize-project scope %) (:data response))))

(defn resolve-project
  [app-config ref]
  (let [ref (str/trim (or ref ""))]
    (first (filter #(= (str/lower-case (or (:display-id %) "")) (str/lower-case ref))
                   (projects app-config)))))

(defn tags
  [app-config]
  (let [scope (site-scope app-config)
        gid (workspace-gid app-config)
        response (api! app-config :get (str "/workspaces/" gid "/tags")
                       {:limit 100 :opt_fields "name"})]
    (mapv #(normalize-tag scope %) (:data response))))

(defn resolve-labels
  [app-config label-refs _scope]
  (let [available (tags app-config)]
    (mapv (fn [ref]
            (or (first (filter #(= (str/lower-case (or (:display-id %) "")) (str/lower-case ref))
                               available))
                (throw (ex-info (str "Asana tag not found: " ref)
                                {:code :label-not-found :label ref}))))
          (distinct (or label-refs [])))))

(defn tag-ids
  [labels]
  (mapv #(get-in % [:ref :id]) labels))

(defn create-task!
  [app-config context title description labels]
  (let [project (some-> (:project context) provider-id)
        parent (some-> (:parent context) provider-id)
        payload {:data (cond-> {:name title :notes description}
                         project (assoc :projects [project])
                         parent (assoc :parent parent)
                         (seq labels) (assoc :tags (vec (tag-ids labels))))}]
    (get (api! app-config :post "/tasks" payload) :data)))

(defn update-task!
  [app-config item-id input]
  (get (api! app-config :put (str "/tasks/" item-id) {:data input}) :data))

(defn create-item-from-intent!
  [app-config context {:keys [title description labels]}]
  (normalize-task (site-scope app-config)
                  (create-task! app-config context title description labels)))

(defn update-item-from-intent!
  [app-config item {:keys [description labels]}]
  (normalize-task (site-scope app-config)
                  (update-task! app-config (provider-id item)
                                {:notes description :tags (vec (tag-ids labels))})))

(defn configured-scope
  [app-config]
  (site-scope app-config))

(defn assert-ready!
  [app-config]
  (config/assert-settings! :asana (:tracker app-config) [:token]))

(defn setup
  [app-config]
  (when (str/blank? (token app-config))
    (throw (ex-info "Asana requires ASANA_TOKEN. Set it, then re-run `ttt setup`." {:code :aborted})))
  (let [me (api! app-config :get "/users/me" nil)]
    (println (str "Asana tracker: authenticated as " (get-in me [:data :name])))
    nil))

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
  {:provider :asana
   :capabilities capabilities
   :configured-scope #(configured-scope app-config)
   :search-parent-items #(tasks app-config)
   :resolve-parent-item #(resolve-item app-config %)
   :resolve-item #(resolve-item app-config %)
   :search-projects #(projects app-config)
   :resolve-project #(resolve-project app-config %)
   :search-labels #(tags app-config)
   :resolve-labels #(resolve-labels app-config %1 %2)
   :create-item! #(create-item-from-intent! app-config %1 %2)
   :update-item! #(update-item-from-intent! app-config %1 %2)})
