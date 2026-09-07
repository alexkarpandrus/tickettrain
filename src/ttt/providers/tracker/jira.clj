(ns ttt.providers.tracker.jira
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.config :as config]
            [ttt.domain :as domain]))

(def api-path "/rest/api/3")

(def key-pattern #"^[A-Z][A-Z0-9_]*-[0-9]+$")

(defn base-url
  [app-config]
  (str/replace (or (get-in app-config [:tracker :site-url]) "") #"/+$" ""))

(defn cloud-id
  [app-config]
  (or (get-in app-config [:tracker :cloud-id]) ""))

(defn email
  [app-config]
  (or (get-in app-config [:tracker :email]) ""))

(defn api-token
  [app-config]
  (or (get-in app-config [:tracker :api-token]) ""))

(defn api-base-url
  [app-config]
  (str "https://api.atlassian.com/ex/jira/" (cloud-id app-config)))

(defn auth-header
  [app-config]
  (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes (str (email app-config) ":" (api-token app-config))
                                            "UTF-8"))))

(defn url-encode
  [value]
  (java.net.URLEncoder/encode (str value) "UTF-8"))

(defn api-endpoint
  [app-config path]
  (str (api-base-url app-config) api-path path))

(defn api!
  [app-config method path query]
  (let [url (api-endpoint app-config path)
        headers {"Authorization" (auth-header app-config)
                 "Content-Type" "application/json"}
        response (case method
                   :get (http/get url {:headers headers :query-params query :throw false})
                   :post (http/post url {:headers headers
                                         :body (json/generate-string query)
                                         :throw false})
                   :put (http/put url {:headers headers
                                       :body (json/generate-string query)
                                       :throw false}))
        status (:status response)
        body (try (json/parse-string (:body response) true) (catch Exception _ nil))]
    (when (>= status 400)
      (throw (ex-info (str "Jira API request failed with status " status ".")
                      {:status status :body body})))
    body))

(defn site-scope
  [app-config]
  (domain/scope-identity :jira (base-url app-config)))

(defn provider-id
  [entity]
  (if (map? entity) (or (get-in entity [:ref :id]) (:id entity)) entity))

(def inline-markdown-pattern
  #"\[((?:\\.|[^\]])*)\]\((https?://[^)\s]+)\)|\*\*([^*]+)\*\*|\x60([^\x60]+)\x60")

(defn text-node
  ([text] {:type "text" :text text})
  ([text mark] {:type "text" :text text :marks [mark]}))

(defn markdown-inlines
  [text]
  (let [matcher (re-matcher inline-markdown-pattern text)]
    (loop [offset 0 nodes []]
      (if (.find matcher)
        (let [start (.start matcher)
              nodes (cond-> nodes
                      (< offset start) (conj (text-node (subs text offset start))))
              [value mark] (cond
                             (.group matcher 1)
                             [(-> (.group matcher 1)
                                  (str/replace "\\[" "")
                                  (str/replace "\\]" ""))
                              {:type "link" :attrs {:href (.group matcher 2)}}]

                             (.group matcher 3)
                             [(.group matcher 3) {:type "strong"}]

                             :else
                             [(.group matcher 4) {:type "code"}])]
          (recur (.end matcher) (conj nodes (text-node value mark))))
        (cond-> nodes
          (< offset (count text)) (conj (text-node (subs text offset))))))))

(defn paragraph
  [text]
  {:type "paragraph" :content (markdown-inlines text)})

(defn bullet-list
  [lines]
  {:type "bulletList"
   :content (mapv (fn [line]
                    {:type "listItem"
                     :content [(paragraph (subs line 2))]})
                  lines)})

(defn text->adf
  [text]
  {:type "doc"
   :version 1
   :content
   (loop [lines (str/split-lines (or text ""))
          content []]
     (if-let [line (first lines)]
       (if (str/starts-with? line "- ")
         (let [[items remaining] (split-with #(str/starts-with? % "- ") lines)]
           (recur remaining (conj content (bullet-list items))))
         (if-let [[_ hashes body] (re-matches #"^(#{1,6})\s+(.+)$" line)]
           (recur (rest lines)
                  (conj content {:type "heading"
                                 :attrs {:level (count hashes)}
                                 :content (markdown-inlines body)}))
           (recur (rest lines) (conj content (paragraph line)))))
       content))})

(defn marked-text
  [node]
  (reduce (fn [text mark]
            (case (:type mark)
              "strong" (str "**" text "**")
              "code" (str "`" text "`")
              "link" (str "["
                          (-> text
                              (str/replace "[" "\\[")
                              (str/replace "]" "\\]"))
                          "](" (get-in mark [:attrs :href]) ")")
              text))
          (:text node)
          (:marks node)))

(defn adf->text
  [node]
  (cond
    (string? node) node
    (map? node)
    (case (:type node)
      "text" (marked-text node)
      "hardBreak" "\n"
      "paragraph" (apply str (map adf->text (:content node)))
      "heading" (str (apply str (repeat (get-in node [:attrs :level] 1) "#"))
                     " "
                     (apply str (map adf->text (:content node))))
      "listItem" (str/join "\n" (map adf->text (:content node)))
      "bulletList" (str/join "\n" (map #(str "- " (adf->text %)) (:content node)))
      "doc" (str/join "\n" (map adf->text (:content node)))
      (str/join "\n" (keep adf->text (:content node))))
    (sequential? node) (str/join "\n" (map adf->text node))
    :else ""))

(defn description->text
  [description]
  (cond (string? description) description
        (map? description) (adf->text description)
        :else ""))

(defn normalize-project
  [base-url project]
  (let [key (:key project)]
    {:ref (domain/identity :jira :project key)
     :display-id key
     :title (or (:name project) key)
     :url (str base-url "/browse/" key)
     :scopes [(domain/scope-identity :jira base-url)]}))

(defn normalize-label
  [base-url name]
  {:ref (domain/identity :jira :label name)
   :display-id name
   :scopes [(domain/scope-identity :jira base-url)]})

(defn normalize-parent
  [base-url parent]
  (when parent
    {:ref (domain/identity :jira :tracker-item (:key parent))
     :display-id (:key parent)
     :title (get-in parent [:fields :summary])
     :url (str base-url "/browse/" (:key parent))}))

(defn normalize-item
  [base-url issue]
  (when issue
    (let [fields (:fields issue)
          key (:key issue)]
      {:ref (domain/identity :jira :tracker-item key)
       :display-id key
       :title (:summary fields)
       :description (description->text (:description fields))
       :url (str base-url "/browse/" key)
       :state (when-let [status (:status fields)] {:name (:name status)})
       :scopes [(domain/scope-identity :jira base-url)]
       :project (when-let [p (:project fields)] (normalize-project base-url p))
       :parent (when-let [p (:parent fields)] (normalize-parent base-url p))
       :labels (mapv (partial normalize-label base-url) (or (:labels fields) []))})))

(defn item-by-key
  [app-config key]
  (try
    (normalize-item (base-url app-config)
                    (api! app-config :get (str "/issue/" (url-encode key)) nil))
    (catch Exception ex
      (if (= 404 (:status (ex-data ex))) nil (throw ex)))))

(defn search-issues
  [app-config jql limit]
  (let [response (api! app-config :get "/search/jql"
                       {:jql jql :maxResults limit
                        :fields "summary,description,status,parent,project,labels"})]
    (mapv #(normalize-item (base-url app-config) %) (:issues response))))

(defn parent-items
  [app-config]
  (let [project (get-in app-config [:tracker :project])
        jql (if (str/blank? project)
              "ORDER BY updated DESC"
              (str "project = " project " ORDER BY updated DESC"))
        limit (get-in app-config [:search :parent-fetch-limit] 100)]
    (search-issues app-config jql limit)))

(defn resolve-item
  [app-config item-ref]
  (let [ref (str/trim (or item-ref ""))]
    (if (re-matches key-pattern ref)
      (item-by-key app-config ref)
      (first (search-issues app-config (str "text ~ \"" ref "\" ORDER BY updated DESC") 5)))))

(defn projects
  [app-config]
  (let [response (api! app-config :get "/project/search" {:maxResults 50})]
    (mapv #(normalize-project (base-url app-config) %) (:values response))))

(defn project-by-key
  [app-config key]
  (try
    (normalize-project (base-url app-config)
                       (api! app-config :get (str "/project/" (url-encode key)) nil))
    (catch Exception ex
      (if (= 404 (:status (ex-data ex))) nil (throw ex)))))

(defn resolve-project
  [app-config project-ref]
  (let [ref (str/trim (or project-ref ""))]
    (or (project-by-key app-config (str/upper-case ref))
        (first (filter #(or (= (str/lower-case (or (:display-id %) "")) (str/lower-case ref))
                            (= (str/lower-case (or (:title %) "")) (str/lower-case ref)))
                       (projects app-config))))))

(defn labels
  [app-config]
  (let [response (api! app-config :get "/label" {:maxResults 100})]
    (mapv #(normalize-label (base-url app-config) %) (:values response))))

(defn resolve-labels
  [app-config label-refs _scope]
  (mapv #(normalize-label (base-url app-config) %) (distinct (or label-refs []))))

(defn label-names
  [labels]
  (mapv #(or (get-in % [:ref :id]) (:display-id %)) labels))

(defn create-item!
  [app-config fields]
  (let [created (api! app-config :post "/issue" {:fields fields})]
    (api! app-config :get (str "/issue/" (url-encode (:key created))) nil)))

(defn update-item!
  [app-config item-id input]
  (api! app-config :put (str "/issue/" (url-encode item-id)) {:fields input})
  (api! app-config :get (str "/issue/" (url-encode item-id)) nil))

(defn create-item-from-intent!
  [app-config context {:keys [title description labels]}]
  (let [parent (some-> (:parent context) provider-id)
        project (when-not parent
                  (or (some-> (:project context) provider-id)
                      (get-in app-config [:tracker :project])))
        issue-type (get-in app-config [:tracker :issue-type] "Task")
        fields (cond-> {:summary title
                        :description (text->adf description)
                        :labels (vec (label-names labels))}
                 parent (assoc :parent {:key parent} :issuetype {:name "Sub-task"})
                 (and (not parent) project) (assoc :project {:key project}
                                                   :issuetype {:name issue-type}))]
    (normalize-item (base-url app-config) (create-item! app-config fields))))

(defn update-item-from-intent!
  [app-config item {:keys [description labels]}]
  (normalize-item (base-url app-config)
                  (update-item! app-config (provider-id item)
                                {:description (text->adf description)
                                 :labels (vec (label-names labels))})))

(defn configured-scope
  [app-config]
  (site-scope app-config))

(defn assert-ready!
  [app-config]
  (config/assert-settings! :jira
                           (:tracker app-config)
                           [:email :api-token :site-url :cloud-id]))

(defn setup
  [app-config]
  (assert-ready! app-config)
  (let [me (api! app-config :get "/myself" nil)]
    (println (str "Jira tracker: authenticated as " (get-in me [:displayName])))
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
  {:provider :jira
   :capabilities capabilities
   :configured-scope #(configured-scope app-config)
   :search-parent-items #(parent-items app-config)
   :resolve-parent-item #(resolve-item app-config %)
   :resolve-item #(resolve-item app-config %)
   :search-projects #(projects app-config)
   :resolve-project #(resolve-project app-config %)
   :search-labels #(labels app-config)
   :resolve-labels #(resolve-labels app-config %1 %2)
   :create-item! #(create-item-from-intent! app-config %1 %2)
   :update-item! #(update-item-from-intent! app-config %1 %2)})
