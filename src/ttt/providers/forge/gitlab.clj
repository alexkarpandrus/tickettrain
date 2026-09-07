(ns ttt.providers.forge.gitlab
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]))

(def api-version "/api/v4")

(defn base-url
  [app-config]
  (str/replace (or (get-in app-config [:forge :base-url]) "https://gitlab.com")
               #"/+$" ""))

(defn token
  [app-config]
  (or (get-in app-config [:forge :token]) ""))

(defn url-encode
  [value]
  (java.net.URLEncoder/encode (str value) "UTF-8"))

(defn api-endpoint
  [app-config path]
  (str (base-url app-config) api-version path))

(defn api!
  [app-config method path query]
  (let [url (api-endpoint app-config path)
        headers {"PRIVATE-TOKEN" (token app-config)}
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
      (throw (ex-info (str "GitLab API request failed with status " status ".")
                      {:status status :body body})))
    body))

(defn parse-repo-slug
  [url]
  (let [u (str/trim (or url ""))]
    (cond
      (str/starts-with? u "git@")
      (second (re-matches #"^git@[^:]+:(.+?)(?:\.git)?$" u))

      (str/starts-with? u "ssh://")
      (second (re-matches #"^ssh://[^/]+/(.+?)(?:\.git)?$" u))

      (str/starts-with? u "http")
      (second (re-matches #"^https?://[^/]+/(.+?)(?:\.git)?$" u))

      :else u)))

(defn remote-slug
  []
  (or (parse-repo-slug (shell/run "git" "remote" "get-url" "origin"))
      (throw (ex-info "Unable to resolve the GitLab project from the origin remote." {}))))

(defn normalize-repo
  [project]
  (let [slug (or (:path_with_namespace project) (:slug project))]
    {:ref (domain/identity :gitlab :repository slug)
     :display-id slug
     :slug slug
     :default-target-branch (or (:default_branch project)
                                (:default-target-branch project))}))

(defn normalize-change-request
  ([change-request]
   {:ref (domain/identity :gitlab :change-request (:iid change-request))
    :display-id (str "!" (:iid change-request))
    :number (:iid change-request)
    :title (:title change-request)
    :body (:description change-request)
    :url (:web_url change-request)
    :source-branch (:source_branch change-request)
    :target-branch (:target_branch change-request)})
  ([repo change-request]
   (assoc (normalize-change-request change-request)
          :ref (domain/contained-identity :gitlab :change-request
                                          (:slug repo) (:iid change-request))
          :display-id (str (:slug repo) "!" (:iid change-request)))))

(defn current-branch
  []
  (try
    (shell/run "git" "rev-parse" "--abbrev-ref" "HEAD")
    (catch Exception ex
      (throw (ex-info "Unable to resolve the current git branch. Run this inside a checked out git repository." {} ex)))))

(defn current-repo
  [app-config]
  (let [slug (remote-slug)
        project (api! app-config :get (str "/projects/" (url-encode slug)) nil)]
    (normalize-repo (assoc project :slug slug))))

(defn maybe-current-change-request
  [app-config]
  (let [slug (remote-slug)
        branch (current-branch)
        mrs (api! app-config :get
                  (str "/projects/" (url-encode slug) "/merge_requests")
                  {:source_branch branch :state "opened" :scope "all"})]
    (first mrs)))

(defn current-change-request
  [app-config]
  (try
    (or (maybe-current-change-request app-config)
        (throw (ex-info "No current change request found." {})))
    (catch Exception ex
      (throw (ex-info "Unable to resolve a merge request for the current branch." {} ex)))))

(defn inspect-current
  [app-config]
  (let [repository (current-repo app-config)
        change-request (maybe-current-change-request app-config)]
    {:branch (current-branch)
     :repository repository
     :change-request (some->> change-request (normalize-change-request repository))}))

(defn identify-change-request
  [repo change-request]
  (normalize-change-request repo change-request))

(defn prefix-change-request-title
  [item-id title]
  (let [clean-title (-> (or title "")
                        str/trim
                        (str/replace #"^\[[^]]+\]\s*" "")
                        (str/replace #"^[A-Z]+-\d+[:\s-]*" ""))]
    (str "[" item-id "] " clean-title)))

(defn update-change-request!
  [app-config repo-slug iid {:keys [body title]}]
  (api! app-config :put
        (str "/projects/" (url-encode repo-slug) "/merge_requests/" iid)
        (cond-> {}
          (some? body) (assoc :description body)
          (some? title) (assoc :title title)))
  nil)

(defn create-change-request!
  [app-config {:keys [title body base head]}]
  (let [slug (remote-slug)
        mr (api! app-config :post
                 (str "/projects/" (url-encode slug) "/merge_requests")
                 {:source_branch head
                  :target_branch base
                  :title title
                  :description (or body "")})]
    {:number (:iid mr)
     :title (:title mr)
     :body (:description mr)
     :url (:web_url mr)
     :source-branch (:source_branch mr)
     :target-branch (:target_branch mr)}))

(def capabilities
  #{:current-branch
    :maybe-current-change-request
    :current-change-request
    :current-repo
    :inspect-current
    :identify-change-request
    :update-change-request!
    :create-change-request!
    :prefix-change-request-title})

(defn assert-ready!
  [app-config]
  (when (str/blank? (token app-config))
    (throw (ex-info "GitLab forge requires a token (set GITLAB_TOKEN or :forge :token)."
                    {:code :provider-config-invalid}))))

(defn setup
  [app-config]
  (when (str/blank? (token app-config))
    (throw (ex-info "No GitLab token configured. Set GITLAB_TOKEN, then re-run `ttt setup`." {:code :aborted})))
  (println (str "GitLab forge: authenticated for " (:display-id (current-repo app-config))))
  nil)

(defn neutral-adapter
  [app-config]
  {:provider :gitlab
   :capabilities capabilities
   :current-branch current-branch
   :maybe-current-change-request #(maybe-current-change-request app-config)
   :current-change-request #(current-change-request app-config)
   :current-repo #(current-repo app-config)
   :inspect-current #(inspect-current app-config)
   :identify-change-request identify-change-request
   :update-change-request! #(update-change-request! app-config %1 %2 %3)
   :create-change-request! #(create-change-request! app-config %)
   :prefix-change-request-title prefix-change-request-title})
