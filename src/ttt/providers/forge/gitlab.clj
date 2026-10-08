(ns ttt.providers.forge.gitlab
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]
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
        headers {"PRIVATE-TOKEN" (token app-config)
                 "Content-Type" "application/json"}]
    (remote/request!
     :gitlab
     #(case method
        :get (http/get url {:headers headers :query-params query :throw false})
        :post (http/post url {:headers headers
                              :body (json/generate-string query)
                              :throw false})
        :put (http/put url {:headers headers
                            :body (json/generate-string query)
                            :throw false})))))

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
    :state (case (:state change-request) "opened" "open" "closed" "closed" "merged" "merged" nil)
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

(defn get-change-request
  [app-config repo change-request-number]
  (let [slug (:display-id repo)
        change-request (api! app-config :get
                             (str "/projects/" (url-encode slug)
                                  "/merge_requests/" change-request-number)
                             nil)]
    (normalize-change-request repo change-request)))

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

(defn comment-change-request!
  [app-config repo-slug iid body]
  (api! app-config :post
        (str "/projects/" (url-encode repo-slug) "/merge_requests/" iid "/notes")
        {:body body})
  nil)

(defn close-change-request!
  [app-config repo-slug iid comment]
  (when comment
    (comment-change-request! app-config repo-slug iid comment))
  (api! app-config :put
        (str "/projects/" (url-encode repo-slug) "/merge_requests/" iid)
        {:state_event "close"})
  nil)

(defn create-change-request!
  [app-config {:keys [title body base head]}]
  (let [slug (remote-slug)]
    (api! app-config :post
          (str "/projects/" (url-encode slug) "/merge_requests")
          {:source_branch head
           :target_branch base
           :title title
           :description (or body "")})))


(defn paginated-get
  [app-config path query]
  (loop [page 1 result []]
    (let [items (api! app-config :get path (assoc query :page page :per_page 100))]
      (when-not (sequential? items)
        (throw (ex-info "GitLab returned an invalid paginated response." {:code :provider-response-invalid})))
      (let [result (into result items)]
        (if (= 100 (count items)) (recur (inc page) result) result)))))

(defn normalize-reviewer
  [app-config user]
  {:ref (domain/contained-identity :gitlab :user (base-url app-config) (:id user))
   :display-id (:username user)
   :title (:name user)})

(defn feedback-path
  [change-request]
  (str "/projects/" (url-encode (get-in change-request [:native-ref :container]))
       "/merge_requests/" (url-encode (get-in change-request [:ref :id]))))

(defn normalize-discussion
  [app-config repository mr discussion]
  (let [container (str (get-in repository [:ref :id]) "!" (:iid mr))
        notes (mapv (fn [note]
                      (when-not (and (= (:project_id mr) (:project_id note))
                                     (= (:id mr) (:noteable_id note))
                                     (= "MergeRequest" (:noteable_type note)))
                        (throw (ex-info "GitLab returned a note outside the selected merge request."
                                        {:code :feedback-identity-mismatch})))
                      {:ref (domain/contained-identity :gitlab :note container (:id note))
                       :display-id (str (:id note))
                       :body (:body note)
                       :author (normalize-reviewer app-config (:author note))
                       :created-at (:created_at note)
                       :updated-at (:updated_at note)
                       :system? (boolean (:system note))
                       :resolvable (boolean (:resolvable note))
                       :resolved (boolean (:resolved note))
                       :position (:position note)})
                    (:notes discussion))
        resolvable (filter :resolvable notes)]
    {:ref (domain/contained-identity :gitlab :discussion container (:id discussion))
     :display-id (:id discussion)
     :individual? (boolean (:individual_note discussion))
     :resolvable (boolean (seq resolvable))
     :resolved (boolean (and (seq resolvable) (every? :resolved resolvable)))
     :notes notes}))

(defn get-feedback
  [app-config repository reference]
  (let [slug (get-in repository [:ref :id])
        _ (when-not (= slug (remote-slug))
            (throw (ex-info "The feedback project does not match the current repository."
                            {:code :feedback-identity-mismatch})))
        project (api! app-config :get (str "/projects/" (url-encode slug)) nil)
        mr-path (str "/projects/" (url-encode slug) "/merge_requests/" (url-encode reference))
        mr (api! app-config :get mr-path nil)]
    (when-not (and (= slug (:path_with_namespace project))
                   (integer? (:id project)) (pos? (:id project))
                   (= (:id project) (:project_id mr))
                   (= (str reference) (str (:iid mr)))
                   (integer? (:id mr)) (pos? (:id mr))
                   (not (str/blank? (:sha mr))))
      (throw (ex-info "GitLab returned a different project, merge request, or missing source head."
                      {:code :feedback-identity-mismatch})))
    (let [discussions (mapv #(normalize-discussion app-config repository mr %)
                            (paginated-get app-config (str mr-path "/discussions") {}))]
      {:change-request (assoc (normalize-change-request repository mr)
                              :head-sha (:sha mr)
                              :native-ref (domain/contained-identity :gitlab :change-request
                                                           (str (:id project)) (str (:id mr))))
       :reviewers (mapv #(normalize-reviewer app-config %) (:reviewers mr))
       :discussions discussions
       :unresolved (mapv :ref (filter #(and (:resolvable %) (not (:resolved %))) discussions))})))

(defn resolve-reviewer
  [app-config reference]
  (let [user (api! app-config :get (str "/users/" (url-encode reference)) nil)]
    (when-not (and (= (str reference) (str (:id user))) (= "active" (:state user)))
      (throw (ex-info "GitLab reviewer ID does not identify an active user."
                      {:code :reviewer-not-found})))
    (normalize-reviewer app-config user)))

(defn apply-feedback!
  [app-config repository change-request operation]
  (let [path (feedback-path change-request)
        discussion (some-> operation :discussion :ref :id url-encode)
        note (some-> operation :note :ref :id url-encode)]
    (case (:type operation)
      :reply (let [created (api! app-config :post (str path "/discussions/" discussion "/notes")
                                {:body (:body operation)})]
               (when-not (some? (:id created))
                 (throw (ex-info "GitLab did not return the created reply ID."
                                 {:code :provider-response-invalid})))
               {:note-id (str (:id created))})
      :edit-note (do (api! app-config :put (str path "/discussions/" discussion "/notes/" note)
                           {:body (:body operation)})
                     {:note-id (get-in operation [:note :ref :id])})
      :resolve-discussion (do (api! app-config :put (str path "/discussions/" discussion)
                                    {:resolved (:resolved operation)})
                              {:discussion-id (get-in operation [:discussion :ref :id])})
      :update-reviewers (do (api! app-config :put path
                                  {:reviewer_ids (mapv #(parse-long (get-in % [:ref :id])) (:reviewers operation))})
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
