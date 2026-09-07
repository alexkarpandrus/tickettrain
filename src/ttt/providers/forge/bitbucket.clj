(ns ttt.providers.forge.bitbucket
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]))

(def api-version "/2.0")

(defn base-url
  [app-config]
  (str/replace (or (get-in app-config [:forge :base-url]) "https://api.bitbucket.org")
               #"/+$" ""))

(defn email
  [app-config]
  (or (get-in app-config [:forge :email]) ""))

(defn api-token
  [app-config]
  (or (get-in app-config [:forge :api-token]) ""))

(defn api-endpoint
  [app-config path]
  (str (base-url app-config) api-version path))

(defn auth-header
  [app-config]
  (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                 (.getBytes (str (email app-config) ":" (api-token app-config))
                                            "UTF-8"))))

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
      (throw (ex-info (str "Bitbucket API request failed with status " status ".")
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
      (throw (ex-info "Unable to resolve the Bitbucket repository from the origin remote." {}))))

(defn normalize-repo
  [repo]
  (let [slug (:full_name repo)]
    {:ref (domain/identity :bitbucket :repository slug)
     :display-id slug
     :slug slug
     :default-target-branch (get-in repo [:mainbranch :name])}))

(defn normalize-change-request
  ([change-request]
   {:ref (domain/identity :bitbucket :change-request (:id change-request))
    :display-id (str "#" (:id change-request))
    :number (:id change-request)
    :title (:title change-request)
    :body (or (:description change-request) "")
    :url (get-in change-request [:links :html :href])
    :source-branch (get-in change-request [:source :branch :name])
    :target-branch (get-in change-request [:destination :branch :name])})
  ([repo change-request]
   (assoc (normalize-change-request change-request)
          :ref (domain/contained-identity :bitbucket :change-request
                                          (:slug repo) (:id change-request))
          :display-id (str (:slug repo) "#" (:id change-request)))))

(defn current-branch
  []
  (try
    (shell/run "git" "rev-parse" "--abbrev-ref" "HEAD")
    (catch Exception ex
      (throw (ex-info "Unable to resolve the current git branch. Run this inside a checked out git repository." {} ex)))))

(defn current-repo
  [app-config]
  (let [slug (remote-slug)
        repo (api! app-config :get (str "/repositories/" slug) nil)]
    (normalize-repo (assoc repo :slug slug))))

(defn maybe-current-change-request
  [app-config]
  (let [slug (remote-slug)
        branch (current-branch)
        q (str "source.branch.name=\"" branch "\" AND state=\"OPEN\"")
        response (api! app-config :get
                       (str "/repositories/" slug "/pullrequests")
                       {:q q :state "OPEN"})]
    (first (:values response))))

(defn current-change-request
  [app-config]
  (or (maybe-current-change-request app-config)
      (throw (ex-info "No current change request found." {}))))

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
  [app-config repo-slug pr-id {:keys [body title]}]
  (api! app-config :put
        (str "/repositories/" repo-slug "/pullrequests/" pr-id)
        (cond-> {}
          (some? body) (assoc :description body)
          (some? title) (assoc :title title)))
  nil)

(defn create-change-request!
  [app-config {:keys [title body base head]}]
  (let [slug (remote-slug)
        pr (api! app-config :post
                 (str "/repositories/" slug "/pullrequests")
                 {:title title
                  :description (or body "")
                  :source {:branch {:name head}}
                  :destination {:branch {:name base}}})]
    {:number (:id pr)
     :title (:title pr)
     :body (or (:description pr) "")
     :url (get-in pr [:links :html :href])
     :source-branch (get-in pr [:source :branch :name])
     :target-branch (get-in pr [:destination :branch :name])}))

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
  (when (or (str/blank? (email app-config)) (str/blank? (api-token app-config)))
    (throw (ex-info "Bitbucket forge requires BITBUCKET_EMAIL and BITBUCKET_API_TOKEN."
                    {:code :provider-config-invalid}))))

(defn setup
  [app-config]
  (assert-ready! app-config)
  (println (str "Bitbucket forge: authenticated for " (:display-id (current-repo app-config))))
  nil)

(defn neutral-adapter
  [app-config]
  {:provider :bitbucket
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
