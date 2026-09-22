(ns ttt.providers.tracker.asana
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]
            [ttt.providers.tracker.pagination :as pagination]
            [ttt.providers.tracker.state :as state]
            [ttt.text.links :as links]))

(def api-path "/api/1.0")

(def task-fields "name,notes,html_notes,permalink_url,completed,due_at,due_on,start_at,start_on,workspace.gid,projects,projects.name,projects.permalink_url,tags,tags.name,parent,parent.name,dependencies,dependencies.name,dependencies.permalink_url,dependencies.completed")

(def rich-notes-pattern
  #"(?i)<(?:h[12]|ul|ol|p|strong|em|s|u|code|blockquote|pre)\b")

(def target-states [{:id "incomplete" :name "incomplete" :completed false}
                    {:id "completed" :name "completed" :completed true}])

(def inline-markdown-pattern
  #"\[((?:\\.|[^\]])*)\]\((https?://[^)\s]+)\)|\*\*([^*]+)\*\*|\x60([^\x60]+)\x60|_([^_]+)_")

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
        headers {"Authorization" (str "Bearer " (token app-config))
                 "Content-Type" "application/json"}]
    (remote/request!
     :asana
     #(case method
        :get (http/get url {:headers headers :query-params query :throw false})
        :post (http/post url {:headers headers
                              :body (json/generate-string query)
                              :throw false})
        :put (http/put url {:headers headers
                            :body (json/generate-string query)
                            :throw false})))))

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

(defn xml-escape
  [value]
  (-> (str value)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn markdown-label
  [value]
  (-> value
      (str/replace "\\[" "[")
      (str/replace "\\]" "]")))

(defn inline-html
  [text]
  (let [matcher (re-matcher inline-markdown-pattern text)]
    (loop [offset 0 parts []]
      (if (.find matcher)
        (let [start (.start matcher)
              parts (cond-> parts
                      (< offset start) (conj (xml-escape (subs text offset start))))
              html (cond
                     (.group matcher 1)
                     (str "<a href=\"" (xml-escape (.group matcher 2)) "\">"
                          (xml-escape (markdown-label (.group matcher 1))) "</a>")

                     (.group matcher 3)
                     (str "<strong>" (xml-escape (.group matcher 3)) "</strong>")

                     (.group matcher 4)
                     (str "<code>" (xml-escape (.group matcher 4)) "</code>")

                     :else
                     (str "<em>" (xml-escape (.group matcher 5)) "</em>"))]
          (recur (.end matcher) (conj parts html)))
        (apply str (cond-> parts
                     (< offset (count text)) (conj (xml-escape (subs text offset)))))))))

(defn markdown->html
  [text]
  (loop [lines (str/split-lines (or text ""))
         parts ["<body>"]]
    (if-let [line (first lines)]
      (if (str/starts-with? line "- ")
        (let [[items remaining] (split-with #(str/starts-with? % "- ") lines)]
          (recur remaining
                 (conj parts
                       (str "<ul>"
                            (apply str (map #(str "<li>" (inline-html (subs % 2)) "</li>") items))
                            "</ul>\n"))))
        (if-let [[_ hashes body] (re-matches #"^(#{1,6})\s+(.+)$" line)]
          (let [level (min 2 (count hashes))]
            (recur (rest lines)
                   (conj parts (str "<h" level ">" (inline-html body) "</h" level ">\n"))))
          (recur (rest lines)
                 (conj parts (if (str/blank? line) "\n" (str (inline-html line) "\n"))))))
      (str (apply str parts) "</body>"))))

(defn markdown-link-label
  [text]
  (-> text
      (str/replace "[" "\\[")
      (str/replace "]" "\\]")))

(defn xml-unescape
  [text]
  (-> text
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")))

(defn html-links->markdown
  [html]
  (str/replace
   html
   #"(?is)<a\b[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>"
   (fn [[_ href label]]
     (str "[" (markdown-link-label (str/replace label #"(?is)<[^>]+>" "")) "](" href ")"))))

(defn html-list->markdown
  [html tag ordered?]
  (str/replace
   html
   (re-pattern (str "(?is)<" tag "\\b[^>]*>(.*?)</" tag ">"))
   (fn [[_ body]]
     (str "\n"
          (str/join
           "\n"
           (map-indexed
            (fn [index [_ item]]
              (str (if ordered? (str (inc index) ". ") "- ")
                   (str/trim (str/replace item #"(?is)<[^>]+>" ""))))
            (re-seq #"(?is)<li\b[^>]*>(.*?)</li>" body)))
          "\n\n"))))

(defn html->markdown
  [html]
  (when (re-find #"(?i)<!DOCTYPE|<!ENTITY" html)
    (throw (ex-info "Asana rich text contains a forbidden XML declaration."
                    {:code :provider-data-invalid})))
  ;; ponytail: handles Asana's documented task tags; use an XML parser if Babashka exposes one.
  (-> html
      html-links->markdown
      (str/replace #"(?is)<strong\b[^>]*>(.*?)</strong>" "**$1**")
      (str/replace #"(?is)<em\b[^>]*>(.*?)</em>" "_$1_")
      (str/replace #"(?is)<code\b[^>]*>(.*?)</code>" "`$1`")
      (str/replace #"(?is)<h1\b[^>]*>(.*?)</h1>" "\n# $1\n\n")
      (str/replace #"(?is)<h2\b[^>]*>(.*?)</h2>" "\n## $1\n\n")
      (html-list->markdown "ol" true)
      (html-list->markdown "ul" false)
      (str/replace #"(?is)<blockquote\b[^>]*>(.*?)</blockquote>" "\n> $1\n\n")
      (str/replace #"(?is)<pre\b[^>]*>(.*?)</pre>" "\n```\n$1\n```\n\n")
      (str/replace #"(?is)<p\b[^>]*>(.*?)</p>" "$1\n\n")
      (str/replace #"(?i)<br\s*/?>" "\n")
      (str/replace #"(?i)<hr\s*/?>" "\n---\n\n")
      (str/replace #"(?is)</?(?:body|s|u)\b[^>]*>" "")
      (str/replace #"(?is)<[^>]+>" "")
      xml-unescape
      (str/replace #"\n{3,}" "\n\n")
      str/trim))


(def managed-heading-pattern
  #"(?s)(?i:<h2\b[^>]*>)\s*(?:(?i:<(?:s|u)\b[^>]*>)\s*)*Pull requests\s*(?:(?i:</(?:s|u)>)\s*)*(?i:</h2>)")

(def next-heading-pattern
  #"(?is)<h[1-6]\b[^>]*>")

(defn html-body-content
  [html]
  (or (second (re-matches #"(?is)^<body>(.*)</body>$" html)) html))

(defn update-description-html
  [item description]
  (let [original (:provider-description item)]
    (if (str/blank? original)
      (markdown->html description)
      (let [section (links/parse-managed description)]
        (when-not section
          (throw (ex-info "The Asana update is missing its managed Pull requests section."
                          {:code :malformed-managed-section})))
        (let [replacement (html-body-content (markdown->html (:content section)))
              matcher (re-matcher managed-heading-pattern original)
              body-end (str/last-index-of (str/lower-case original) "</body>")]
          (if (.find matcher)
            (let [start (.start matcher)
                  after-heading (.end matcher)
                  next-matcher (re-matcher next-heading-pattern (subs original after-heading))
                  end (cond
                        (.find next-matcher) (+ after-heading (.start next-matcher))
                        body-end body-end
                        :else (count original))]
              (str (subs original 0 start) replacement (subs original end)))
            (let [end (or body-end (count original))]
              (str (subs original 0 end) replacement (subs original end)))))))))

(defn normalize-project
  [scope project]
  {:ref (domain/identity :asana :project (:gid project))
   :display-id (:name project)
   :title (:name project)
   :url (:permalink_url project)
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

(defn normalize-date-time
  [date-time date]
  (or date-time (when date (str date "T00:00:00Z"))))

(defn normalize-task-summary
  [scope task]
  {:ref (domain/identity :asana :tracker-item (:gid task))
   :display-id (:gid task)
   :title (:name task)
   :url (:permalink_url task)
   :state (if (:completed task) "completed" "open")
   :scopes [scope]})

(defn normalize-task
  [scope task]
  (when task
    (let [scope (if-let [workspace-gid (get-in task [:workspace :gid])]
                  (domain/scope-identity :asana workspace-gid)
                  scope)
          html-notes (or (:html_notes task) "")
          rich-notes? (boolean (re-find rich-notes-pattern html-notes))]
      {:ref (domain/identity :asana :tracker-item (:gid task))
       :display-id (:gid task)
       :title (:name task)
       :description (if rich-notes?
                      (html->markdown html-notes)
                      (or (:notes task) ""))
       :provider-description (when rich-notes? html-notes)
       :url (:permalink_url task)
       :state (if (:completed task) "completed" "open")
       :due-at (normalize-date-time (:due_at task) (:due_on task))
       :available-at (normalize-date-time (:start_at task) (:start_on task))
       :blocked-by (mapv #(normalize-task-summary scope %) (or (:dependencies task) []))
       :scopes [scope]
       :project (when-let [p (first (:projects task))] (normalize-project scope p))
       :parent (when-let [p (:parent task)] (normalize-parent scope p))
       :labels (mapv #(normalize-tag scope %) (or (:tags task) []))})))

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


(defn list-items
  [app-config matches? limit]
  (let [scope (site-scope app-config)
        gid (workspace-gid app-config)]
    (pagination/collect-matches
     (fn [offset]
       (let [response (api! app-config :get "/tasks"
                            (cond-> {:workspace gid
                                     :assignee "me"
                                     :limit 100
                                     :opt_fields task-fields}
                              offset (assoc :offset offset)))]
         {:items (mapv #(normalize-task scope %) (:data response))
          :next-cursor (get-in response [:next_page :offset])}))
     matches?
     limit)))

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
                       {:limit 100 :opt_fields "name,permalink_url"})]
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

(def neutral-completed {"open" false "completed" true})

(defn native-completed
  [neutral-state]
  (if-let [entry (find neutral-completed neutral-state)]
    (val entry)
    (throw (ex-info (str "Asana cannot represent neutral state: " neutral-state)
                    {:code :unsupported-work-item-value
                     :provider :asana
                     :field :state
                     :value neutral-state}))))

(defn native-work-item-input
  [item intent]
  (cond-> {}
    (and (contains? intent :state) (not= (:state intent) (:state item)))
    (assoc :completed (native-completed (:state intent)))

    (contains? intent :due-at)
    (merge (if-let [due-at (:due-at intent)]
             {:due_at due-at}
             {:due_at nil :due_on nil}))

    (contains? intent :available-at)
    (merge (if-let [available-at (:available-at intent)]
             {:start_at available-at}
             {:start_at nil :start_on nil}))))

(defn validate-work-item-intent!
  [_action item intent]
  (native-work-item-input item intent)
  (let [due-at (if (contains? intent :due-at) (:due-at intent) (:due-at item))
        available-at (if (contains? intent :available-at) (:available-at intent) (:available-at item))]
    (when (and available-at (nil? due-at))
      (throw (ex-info "Asana requires dueAt when availableAt is set."
                      {:code :invalid-work-item-fields
                       :provider :asana
                       :fields [:available-at :due-at]}))))
  (when (and item
             (some #(= (provider-id item) (provider-id %)) (:blocked-by intent)))
    (throw (ex-info "An Asana task cannot block itself."
                    {:code :invalid-blocker
                     :provider :asana
                     :item (provider-id item)})))
  intent)

(defn sync-blockers!
  [app-config item blockers]
  (let [item-id (provider-id item)
        existing (set (map provider-id (:blocked-by item)))
        desired (set (map provider-id blockers))]
    (when-let [added (seq (remove existing desired))]
      (api! app-config :post (str "/tasks/" item-id "/addDependencies")
            {:data {:dependencies (vec added)}}))
    (when-let [removed (seq (remove desired existing))]
      (api! app-config :post (str "/tasks/" item-id "/removeDependencies")
            {:data {:dependencies (vec removed)}}))))

(defn create-task!
  ([app-config context title description labels]
   (create-task! app-config context title description labels {}))
  ([app-config context title description labels native-input]
   (let [target (state/resolve-target "Asana"
                                      (get-in app-config [:tracker :target-state])
                                      target-states)
         project (some-> (:project context) provider-id)
         parent (some-> (:parent context) provider-id)
         input (merge
                (cond-> {:name title :html_notes (markdown->html description)}
                  target (assoc :completed (:completed target))
                  project (assoc :projects [project])
                  parent (assoc :parent parent)
                  (and (not project) (not parent)) (assoc :workspace (workspace-gid app-config))
                  (seq labels) (assoc :tags (vec (tag-ids labels))))
                native-input)]
     (get (api! app-config :post "/tasks" {:data input}) :data))))

(defn update-task!
  [app-config item-id input]
  (get (api! app-config :put (str "/tasks/" item-id) {:data input}) :data))

(defn comment-item!
  [app-config item body]
  (api! app-config :post
        (str "/tasks/" (provider-id item) "/stories")
        {:data {:text body}})
  nil)

(defn update-tags!
  [app-config item-id current-labels labels]
  (let [current (set (tag-ids current-labels))
        desired (set (tag-ids labels))]
    (doseq [tag-id (remove desired current)]
      (api! app-config :post (str "/tasks/" item-id "/removeTag") {:data {:tag tag-id}}))
    (doseq [tag-id (remove current desired)]
      (api! app-config :post (str "/tasks/" item-id "/addTag") {:data {:tag tag-id}}))))

(defn create-item-from-intent!
  [app-config context {:keys [title description labels] :as intent}]
  (let [created (normalize-task (site-scope app-config)
                                (create-task! app-config context title description labels
                                              (native-work-item-input nil intent)))]
    (if (contains? intent :blocked-by)
      (try
        (sync-blockers! app-config created (:blocked-by intent))
        (or (task-by-gid app-config (provider-id created))
            (throw (ex-info "Asana task refresh returned no item." {})))
        (catch Exception ex
          (throw (ex-info
                  (str "Asana created " (provider-id created)
                       " but could not apply blockers or refresh it. Inspect the task before retrying.")
                  (assoc (or (ex-data ex) {})
                         :created-item (provider-id created)
                         :preserve-created-item true)
                  ex))))
      created)))

(defn update-item-from-intent!
  [app-config item {:keys [description labels] :as intent}]
  (let [item-id (provider-id item)
        labels (vec (or labels []))
        native-input (native-work-item-input item intent)
        input (cond-> native-input
                (not= description (:description item))
                (assoc :html_notes (update-description-html item description)))]
    (when (seq input)
      (update-task! app-config item-id input))
    (when (not= (set (tag-ids labels)) (set (tag-ids (:labels item))))
      (update-tags! app-config item-id (:labels item) labels))
    (when (contains? intent :blocked-by)
      (sync-blockers! app-config item (:blocked-by intent)))
    (if (or (seq native-input) (contains? intent :blocked-by))
      (task-by-gid app-config item-id)
      (assoc item
             :description description
             :provider-description (if (contains? input :html_notes)
                                     (:html_notes input)
                                     (:provider-description item))
             :labels labels))))

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
  (let [me (api! app-config :get "/users/me" nil)
        target (state/choose-target "Asana"
                                    (get-in app-config [:tracker :target-state])
                                    target-states)]
    (println (str "Asana tracker: authenticated as " (get-in me [:data :name])))
    {:tracker (assoc (select-keys (:tracker app-config) [:token :workspace :base-url])
                     :target-state (some-> target :name))}))

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
  {:provider :asana
   :capabilities capabilities
   :item-capabilities #{:item-lifecycle :item-due-dates :item-availability :item-blockers}
   :configured-scope #(configured-scope app-config)
   :list-items #(list-items app-config %1 %2)
   :search-parent-items #(tasks app-config)
   :resolve-parent-item #(resolve-item app-config %)
   :resolve-item #(resolve-item app-config %)
   :search-projects #(projects app-config)
   :resolve-project #(resolve-project app-config %)
   :search-labels #(tags app-config)
   :resolve-labels #(resolve-labels app-config %1 %2)
   :validate-work-item-intent! #(validate-work-item-intent! %1 %2 %3)
   :create-item! #(create-item-from-intent! app-config %1 %2)
   :comment-item! #(comment-item! app-config %1 %2)
   :update-item! #(update-item-from-intent! app-config %1 %2)})
