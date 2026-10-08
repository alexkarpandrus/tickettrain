(ns ttt.providers.forge.bitbucket
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]
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
                 "Content-Type" "application/json"}]
    (remote/request!
     :bitbucket
     #(case method
        :get (http/get url {:headers headers :query-params query :throw false})
        :post (http/post url {:headers headers
                              :body (json/generate-string query)
                              :throw false})
        :put (http/put url {:headers headers
                            :body (json/generate-string query)
                            :throw false})
        :delete (http/delete url {:headers headers :throw false})))))

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

(defn encode-body
  [body]
  (str/replace (or body "")
               #"(?m)^<!-- ttt:(begin|end|item [^>]+) -->$"
               (fn [[_ marker]] (str "[//]: # (ttt:" marker ")"))))

(defn decode-body
  [body]
  (str/replace (or body "")
               #"(?m)^\[//\]: # \(ttt:(begin|end|item [^)]+)\)$"
               (fn [[_ marker]] (str "<!-- ttt:" marker " -->"))))

(defn normalize-change-request
  ([change-request]
   {:ref (domain/identity :bitbucket :change-request (:id change-request))
    :display-id (str "#" (:id change-request))
    :number (:id change-request)
    :title (:title change-request)
    :body (decode-body (:description change-request))
    :url (get-in change-request [:links :html :href])
    :state (case (:state change-request) "OPEN" "open" "MERGED" "merged" "DECLINED" "closed" nil)
    :metadata-editable? (= "OPEN" (:state change-request))
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

(defn get-change-request
  [app-config repo change-request-number]
  (let [slug (:display-id repo)
        change-request (api! app-config :get
                             (str "/repositories/" slug "/pullrequests/" change-request-number)
                             nil)]
    (normalize-change-request repo change-request)))

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
          (some? body) (assoc :description (encode-body body))
          (some? title) (assoc :title title)))
  nil)

(defn comment-change-request!
  [app-config repo-slug pr-id body]
  (api! app-config :post
        (str "/repositories/" repo-slug "/pullrequests/" pr-id "/comments")
        {:content {:raw body}})
  nil)

(defn close-change-request!
  [app-config repo-slug pr-id comment]
  (when comment
    (comment-change-request! app-config repo-slug pr-id comment))
  (api! app-config :post
        (str "/repositories/" repo-slug "/pullrequests/" pr-id "/decline")
        nil)
  nil)

(defn create-change-request!
  [app-config {:keys [title body base head]}]
  (let [slug (remote-slug)
        path (str "/repositories/" slug "/pullrequests")]
    (api! app-config :post path
          {:title title
           :description (encode-body body)
           :source {:branch {:name head}}
           :destination {:branch {:name base}}})))


;; Bitbucket review discussions are comment trees, not top-level PR comments.
(defn url-encode [value]
  (java.net.URLEncoder/encode (str value) "UTF-8"))

(defn uuid? [value]
  (and (string? value)
       (boolean (re-matches #"\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\}" value))))

(defn feedback-pages [app-config path]
  (let [endpoint (java.net.URI. (api-endpoint app-config path))]
    (loop [query {"pagelen" "100"} seen #{} result []]
      (let [page (api! app-config :get path query)]
        (when-not (vector? (:values page))
          (throw (ex-info "Bitbucket returned an invalid comments page." {:code :provider-response-invalid})))
        (let [result (into result (:values page))]
          (if-let [next (:next page)]
            (let [uri (try (.resolve endpoint next)
                           (catch Exception _
                             (throw (ex-info "Bitbucket returned a malformed comments-page URL."
                                             {:code :provider-response-invalid}))))]
              (when-not (and (= (.getScheme endpoint) (.getScheme uri))
                             (= (.getAuthority endpoint) (.getAuthority uri))
                             (= (.getPath endpoint) (.getPath uri))
                             (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                             (not (str/blank? (.getRawQuery uri)))
                             (not (contains? seen (str uri))))
                (throw (ex-info "Bitbucket returned an unsafe or repeated comments page." {:code :provider-response-invalid})))
              (let [params (into {} (map (fn [part]
                                          (let [[key value] (str/split part #"=" 2)]
                                            [(java.net.URLDecoder/decode key "UTF-8")
                                             (java.net.URLDecoder/decode (or value "") "UTF-8")]))
                                        (str/split (.getRawQuery uri) #"&")))]
                (when-not (every? #{"page" "pagelen"} (keys params))
                  (throw (ex-info "Bitbucket pagination changed the feedback query." {:code :provider-response-invalid})))
                (recur params (conj seen (str uri)) result)))
            result))))))

(defn normalize-reviewer [app-config user]
  (when-not (uuid? (:uuid user))
    (throw (ex-info "Bitbucket returned an invalid user UUID." {:code :provider-response-invalid})))
  {:ref (domain/contained-identity :bitbucket :user (base-url app-config) (:uuid user))
   :display-id (or (:nickname user) (:display_name user) (:uuid user))
   :title (:display_name user)})

(defn normalize-feedback-discussions [app-config container pr viewer comments]
  (when-not (= (count comments) (count (distinct (map :id comments))))
    (throw (ex-info "Bitbucket returned duplicate comment IDs." {:code :provider-response-invalid})))
  (doseq [comment comments]
    (when-not (and (integer? (:id comment)) (pos? (:id comment))
                   (= (:id pr) (get-in comment [:pullrequest :id]))
                   (boolean? (:deleted comment))
                   (or (nil? (:parent comment))
                       (and (integer? (get-in comment [:parent :id])) (pos? (get-in comment [:parent :id])))))
      (throw (ex-info "Bitbucket returned a comment outside the selected PR." {:code :feedback-identity-mismatch}))))
  (let [by-id (into {} (map (juxt :id identity) comments))
        root-id (fn [comment]
                  (loop [current comment seen #{}]
                    (when (contains? seen (:id current))
                      (throw (ex-info "Bitbucket returned a cyclic comment tree." {:code :provider-response-invalid})))
                    (if-let [parent (get-in current [:parent :id])]
                      (let [next (get by-id parent)]
                        (when-not next
                          (throw (ex-info "Bitbucket returned a comment without its parent." {:code :provider-response-invalid})))
                        (recur next (conj seen (:id current))))
                      (:id current))))
        groups (group-by root-id comments)]
    (mapv (fn [root]
            (let [id (:id root) deleted? (:deleted root)
                  resolved? (some? (:resolution root))]
              {:ref (domain/contained-identity :bitbucket :discussion container id)
               :display-id (str id) :individual? false :replyable? (not deleted?)
               :resolvable (not deleted?) :resolved resolved?
               :notes (mapv (fn [note]
                              {:ref (domain/contained-identity :bitbucket :note container (:id note))
                               :display-id (str (:id note)) :body (get-in note [:content :raw])
                               :author (when (:user note) (normalize-reviewer app-config (:user note)))
                               :created-at (:created_on note) :updated-at (:updated_on note)
                               :system? (:deleted note)
                               :editable? (and (not (:deleted note)) (= (:uuid viewer) (get-in note [:user :uuid])))
                               :position (:inline note)})
                            (get groups id))}))
          (filter #(nil? (:parent %)) comments))))

(defn get-feedback [app-config repository reference]
  (let [slug (:display-id repository)
        _ (when-not (= slug (remote-slug))
            (throw (ex-info "Bitbucket feedback repository changed." {:code :feedback-identity-mismatch})))
        [workspace-name repo-name] (str/split slug #"/" 2)
        workspace (api! app-config :get (str "/workspaces/" (url-encode workspace-name)) nil)
        repo (api! app-config :get (str "/repositories/" (url-encode workspace-name) "/" (url-encode repo-name)) nil)
        _ (when-not (and (= workspace-name (:slug workspace)) (uuid? (:uuid workspace))
                          (= slug (:full_name repo)) (uuid? (:uuid repo)))
            (throw (ex-info "Bitbucket returned a different workspace or repository." {:code :feedback-identity-mismatch})))
        path (str "/repositories/" (url-encode (:uuid workspace)) "/" (url-encode (:uuid repo))
                  "/pullrequests/" (url-encode reference))
        pr (api! app-config :get path nil)
        viewer (api! app-config :get "/user" nil)]
    (when-not (and (= reference (str (:id pr))) (= (:uuid repo) (get-in pr [:destination :repository :uuid]))
                   (not (str/blank? (get-in pr [:source :commit :hash])))
                   (uuid? (:uuid viewer)) (vector? (:reviewers pr)))
      (throw (ex-info "Bitbucket returned a different PR, user, or missing source head." {:code :feedback-identity-mismatch})))
    (let [native-container (str (base-url app-config) "/" (:uuid workspace) "/" (:uuid repo))
          discussions (normalize-feedback-discussions
                       app-config (str native-container "#" reference) pr viewer
                       (feedback-pages app-config (str path "/comments")))]
      {:change-request (assoc (normalize-change-request repository pr)
                              :head-sha (get-in pr [:source :commit :hash])
                              :native-ref (domain/contained-identity :bitbucket :change-request native-container reference))
       :reviewers (mapv #(normalize-reviewer app-config %) (:reviewers pr))
       :discussions discussions
       :unresolved (mapv :ref (filter #(and (:resolvable %) (not (:resolved %))) discussions))})))

(defn resolve-reviewer [app-config reference]
  (when-not (uuid? reference)
    (throw (ex-info "Bitbucket reviewers require UUID strings from inspection." {:code :invalid-request})))
  (let [user (api! app-config :get (str "/users/" (url-encode reference)) nil)]
    (when-not (= reference (:uuid user))
      (throw (ex-info "Bitbucket returned a different reviewer." {:code :reviewer-not-found})))
    (normalize-reviewer app-config user)))

(defn apply-feedback! [app-config _repository change-request operation]
  (let [[workspace repository] (take-last 2 (str/split (get-in change-request [:native-ref :container]) #"/"))
        path (str "/repositories/" (url-encode workspace) "/" (url-encode repository)
                  "/pullrequests/" (url-encode (get-in change-request [:native-ref :id])))
        discussion (get-in operation [:discussion :ref :id])
        note (get-in operation [:note :ref :id])]
    (case (:type operation)
      :reply (let [created (api! app-config :post (str path "/comments")
                                {:parent {:id (parse-long discussion)} :content {:raw (:body operation)}})]
               (when-not (and (integer? (:id created)) (pos? (:id created)))
                 (throw (ex-info "Bitbucket did not return the reply ID." {:code :provider-response-invalid})))
               {:note-id (str (:id created))})
      :edit-note (do (api! app-config :put (str path "/comments/" (url-encode note)) {:content {:raw (:body operation)}})
                     {:note-id note})
      :resolve-discussion (do (api! app-config (if (:resolved operation) :post :delete)
                                    (str path "/comments/" (url-encode discussion) "/resolve") nil)
                              {:discussion-id discussion})
      :update-reviewers (do (api! app-config :put path
                                  {:reviewers (mapv #(hash-map :uuid (get-in % [:ref :id])) (:reviewers operation))})
                            {:reviewer-ids (mapv #(get-in % [:ref :id]) (:reviewers operation))}))))
(def capabilities
  #{:current-branch
    :maybe-current-change-request
    :current-change-request
    :current-repo
    :inspect-current
    :identify-change-request
    :get-change-request
    :update-change-request!
    :comment-change-request!
    :create-change-request!
    :close-change-request!
    :get-feedback
    :resolve-reviewer
    :apply-feedback!
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
   :feedback-capabilities #{:reply :edit-note :resolve-discussion :update-reviewers}
   :get-feedback #(get-feedback app-config %1 %2)
   :resolve-reviewer #(resolve-reviewer app-config %)
   :apply-feedback! #(apply-feedback! app-config %1 %2 %3)
   :current-branch current-branch
   :maybe-current-change-request #(maybe-current-change-request app-config)
   :current-change-request #(current-change-request app-config)
   :current-repo #(current-repo app-config)
   :inspect-current #(inspect-current app-config)
   :identify-change-request identify-change-request
   :get-change-request #(get-change-request app-config %1 %2)
   :update-change-request! #(update-change-request! app-config %1 %2 %3)
   :comment-change-request! #(comment-change-request! app-config %1 %2 %3)
   :create-change-request! #(create-change-request! app-config %)
   :close-change-request! #(close-change-request! app-config %1 %2 %3)
   :prefix-change-request-title prefix-change-request-title})
