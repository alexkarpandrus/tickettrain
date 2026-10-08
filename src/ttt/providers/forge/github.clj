(ns ttt.providers.forge.github
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]))

(def change-request-fields
  "number,title,body,url,headRefName,baseRefName,state")

(def repo-fields
  "nameWithOwner,defaultBranchRef")

(defn normalize-change-request
  ([change-request]
   {:ref (domain/identity :github :change-request (:number change-request))
    :display-id (str "#" (:number change-request))
    :number (:number change-request)
    :title (:title change-request)
    :body (:body change-request)
    :url (:url change-request)
    :state (some-> change-request :state str/lower-case)
    :source-branch (or (:source-branch change-request)
                       (:headRefName change-request))
    :target-branch (or (:target-branch change-request)
                       (:baseRefName change-request))})
  ([repo change-request]
   (assoc (normalize-change-request change-request)
          :ref (domain/contained-identity :github
                                          :change-request
                                          (:display-id repo)
                                          (:number change-request))
          :display-id (str (:display-id repo) "#" (:number change-request)))))

(defn normalize-repo
  [repo]
  (let [slug (or (:nameWithOwner repo) (:slug repo))]
    {:ref (domain/identity :github :repository slug)
     :display-id slug
     :slug slug
     :default-target-branch (or (get-in repo [:defaultBranchRef :name])
                                (:default-target-branch repo))}))

(defn connectivity-error?
  [text]
  (let [value (str/lower-case (or text ""))]
    (boolean
     (some #(str/includes? value %)
           ["error connecting to api.github.com"
            "could not resolve host"
            "dial tcp"
            "tls handshake timeout"
            "i/o timeout"
            "connection refused"
            "connection reset by peer"]))))

(defn maybe-current-change-request
  []
  (try
    (let [change-request (-> (shell/run "gh" "pr" "view" "--json" change-request-fields)
                             (json/parse-string true))]
      (when (= "OPEN" (:state change-request))
        (normalize-change-request change-request)))
    (catch Exception ex
      (let [data (ex-data ex)
            text (str/lower-case (str (:err data) " " (:out data)))]
        (if (str/includes? text "no pull requests found")
          nil
          (if (connectivity-error? text)
            (throw (ex-info
                    "Unable to reach GitHub via gh while checking the current pull request."
                    {:kind :github-connectivity}
                    ex))
            (throw ex)))))))

(defn current-change-request
  []
  (try
    (or (maybe-current-change-request)
        (throw (ex-info "No current change request found." {})))
    (catch Exception ex
      (throw (ex-info
              "Unable to resolve a pull request for the current branch. Run this inside a git repo with an open PR and a configured GitHub CLI session."
              {}
              ex)))))

(defn current-repo
  []
  (try
    (-> (shell/run "gh" "repo" "view" "--json" repo-fields)
        (json/parse-string true)
        normalize-repo)
    (catch Exception ex
      (throw (ex-info
              "Unable to resolve the current GitHub repository via gh."
              {}
              ex)))))

(defn get-change-request
  [repo change-request-number]
  (let [change-request (-> (shell/run "gh" "pr" "view" (str change-request-number)
                                      "--repo" (:display-id repo)
                                      "--json" change-request-fields)
                           (json/parse-string true))]
    (normalize-change-request repo change-request)))

(defn current-branch
  []
  (try
    (shell/run "git" "rev-parse" "--abbrev-ref" "HEAD")
    (catch Exception ex
      (throw (ex-info
              "Unable to resolve the current git branch. Run this inside a checked out git repository."
              {}
              ex)))))

(defn prefix-change-request-title
  [item-id title]
  (let [clean-title (-> (or title "")
                        str/trim
                        (str/replace #"^\[[^]]+\]\s*" "")
                        (str/replace #"^[A-Z]+-\d+[:\s-]*" ""))]
    (str "[" item-id "] " clean-title)))

(defn update-change-request!
  [repo-slug change-request-number {:keys [body title]}]
  (let [tmp-file (java.io.File/createTempFile "ttt-pr-body" ".json")]
    (spit tmp-file (json/generate-string (cond-> {}
                                          body (assoc :body body)
                                          title (assoc :title title))))
    (try
      (try
        (shell/run "gh" "api"
                   (str "repos/" repo-slug "/pulls/" change-request-number)
                   "--method" "PATCH"
                   "--input" (.getAbsolutePath tmp-file))
        (catch Exception ex
          (throw (ex-info
                  (str "Unable to update GitHub PR #" change-request-number ".")
                  {:repo repo-slug
                   :pr-number change-request-number}
                  ex))))
      (finally
        (io/delete-file tmp-file true)))))

(defn comment-change-request!
  [repo-slug change-request-number body]
  (shell/run "gh" "pr" "comment" (str change-request-number)
             "--repo" repo-slug
             "--body" body)
  nil)

(defn close-change-request!
  [repo-slug change-request-number comment]
  (apply shell/run
         (cond-> ["gh" "pr" "close" (str change-request-number) "--repo" repo-slug]
           comment (conj "--comment" comment)))
  nil)

(defn create-change-request!
  [{:keys [title body base head]}]
  (let [tmp-file (java.io.File/createTempFile "ttt-pr-create" ".md")]
    (spit tmp-file (or body ""))
    (try
      (try
        (let [url (shell/run "gh" "pr" "create"
                             "--title" title
                             "--body-file" (.getAbsolutePath tmp-file)
                             "--base" base
                             "--head" head)
              number (some->> (re-find #"/pull/(\d+)" url)
                              second
                              Long/parseLong)]
          {:number number
           :title title
           :body body
           :url url
           :source-branch head
           :target-branch base})
        (catch Exception ex
          (let [text (str (:err (ex-data ex)) " " (:out (ex-data ex)))]
            (throw (ex-info
                    (if (connectivity-error? text)
                      "Unable to create a GitHub pull request because gh could not reach GitHub."
                      "Unable to create a GitHub pull request.")
                    {:base base
                     :head head
                     :title title
                     :kind (when (connectivity-error? text)
                             :github-connectivity)}
                    ex)))))
      (finally
        (io/delete-file tmp-file true)))))

(defn identify-change-request
  [repo change-request]
  (normalize-change-request repo change-request))

(defn inspect-current
  []
  (let [repository (normalize-repo (current-repo))
        change-request (maybe-current-change-request)]
    {:branch (current-branch)
     :repository repository
     :change-request (some->> change-request
                              (normalize-change-request repository))}))

;; Review feedback stays provider-owned; approval and recovery live in ttt.feedback.
(def reviewer-fields
  "__typename ... on Node { id } ... on User { login name } ... on Bot { login }
   ... on Team { slug name organization { login } }")

(def note-fields
  "id body createdAt updatedAt viewerDidAuthor viewerCanUpdate
   author { login ... on Node { id } } pullRequest { id }
   path line startLine originalLine originalStartLine diffHunk")

(def feedback-query
  (str "query Feedback($owner:String!, $name:String!, $number:Int!, $threads:String, $reviewers:String) {
    repository(owner:$owner, name:$name) { id nameWithOwner url
      pullRequest(number:$number) { id number title body url state headRefName baseRefName headRefOid
        reviewRequests(first:100, after:$reviewers) {
          nodes { requestedReviewer { " reviewer-fields " } } pageInfo { hasNextPage endCursor } }
        reviewThreads(first:100, after:$threads) {
          nodes { id isResolved viewerCanReply viewerCanResolve viewerCanUnresolve pullRequest { id }
            comments(first:100) { nodes { " note-fields " } pageInfo { hasNextPage endCursor } } }
          pageInfo { hasNextPage endCursor } } } } }"))

(def comments-query
  (str "query FeedbackComments($id:ID!, $cursor:String) { node(id:$id) {
    ... on PullRequestReviewThread { id pullRequest { id }
      comments(first:100, after:$cursor) { nodes { " note-fields " } pageInfo { hasNextPage endCursor } } } } }"))

(defn graphql!
  [host query variables]
  (let [response (try
                   (-> (shell/run-input (json/generate-string {:query query :variables variables})
                                        "gh" "api" "graphql" "--hostname" host "--input" "-")
                       (json/parse-string true))
                   (catch Exception ex
                     (let [status (some->> (re-find #"\(HTTP (\d{3})\)" (or (:err (ex-data ex)) ""))
                                           second parse-long)]
                       (throw (ex-info "GitHub feedback request failed."
                                       (cond-> {:code :remote-api-error :provider :github}
                                         status (assoc :status status)) ex)))))]
    ;; GraphQL can return errors and partial data with HTTP 200. Never retry an uncertain write.
    (when (or (seq (:errors response)) (not (map? (:data response))))
      (throw (ex-info "GitHub returned GraphQL errors or missing feedback data."
                      {:code :provider-response-invalid :provider :github})))
    (:data response)))

(defn feedback-repository []
  (let [repo (-> (shell/run "gh" "repo" "view" "--json" "nameWithOwner,url")
                 (json/parse-string true))]
    (assoc (normalize-repo repo) :url (:url repo))))
(defn feedback-host [repository]
  (let [uri (java.net.URI. (or (:url repository) ""))]
    (when-not (and (= "https" (.getScheme uri)) (.getHost uri) (nil? (.getUserInfo uri))
                   (= (str "/" (:display-id repository)) (.getPath uri)))
      (throw (ex-info "GitHub feedback requires the exact repository HTTPS URL."
                      {:code :feedback-identity-mismatch})))
    (.getAuthority uri)))

(defn feedback-pages [connection next-page]
  (loop [page connection seen #{} result []]
    (when-not (and (vector? (:nodes page)) (boolean? (get-in page [:pageInfo :hasNextPage])))
      (throw (ex-info "GitHub returned an invalid feedback connection." {:code :provider-response-invalid})))
    (let [result (into result (:nodes page))
          cursor (get-in page [:pageInfo :endCursor])]
      (if (get-in page [:pageInfo :hasNextPage])
        (do (when (or (str/blank? cursor) (contains? seen cursor))
              (throw (ex-info "GitHub returned an invalid feedback cursor." {:code :provider-response-invalid})))
            (recur (next-page cursor) (conj seen cursor) result))
        result))))

(defn normalize-reviewer [host reviewer]
  (let [kind (case (:__typename reviewer)
               "User" :user "Bot" :bot ("Team" "EnterpriseTeam") :team "Mannequin" :mannequin nil)]
    (when-not (and kind (string? (:id reviewer)) (not (str/blank? (:id reviewer))))
      (throw (ex-info "GitHub returned an invalid reviewer identity." {:code :provider-response-invalid})))
    {:ref (domain/contained-identity :github kind host (:id reviewer))
     :display-id (or (:login reviewer)
                     (when (:slug reviewer) (str (get-in reviewer [:organization :login]) "/" (:slug reviewer)))
                     (:id reviewer))
     :title (:name reviewer)}))

(defn normalize-feedback-thread [host repository pr thread]
  (when-not (and (string? (:id thread)) (not (str/blank? (:id thread)))
                 (= (:id pr) (get-in thread [:pullRequest :id]))
                 (every? boolean? (map thread [:isResolved :viewerCanReply :viewerCanResolve :viewerCanUnresolve])))
    (throw (ex-info "GitHub returned a thread outside the selected pull request." {:code :feedback-identity-mismatch})))
  (let [container (str host "/" (:id repository) "#" (:id pr))
        comments (feedback-pages
                  (:comments thread)
                  (fn [cursor]
                    (let [node (:node (graphql! host comments-query {:id (:id thread) :cursor cursor}))]
                      (when-not (and (= (:id thread) (:id node)) (= (:id pr) (get-in node [:pullRequest :id])))
                        (throw (ex-info "GitHub returned comments for another thread." {:code :feedback-identity-mismatch})))
                      (:comments node))))]
    {:ref (domain/contained-identity :github :discussion container (:id thread))
     :display-id (:id thread) :individual? false :resolvable true :resolved (:isResolved thread)
     :replyable? (:viewerCanReply thread) :can-resolve? (:viewerCanResolve thread)
     :can-unresolve? (:viewerCanUnresolve thread)
     :notes (mapv (fn [note]
                    (when-not (and (string? (:id note)) (not (str/blank? (:id note)))
                                   (= (:id pr) (get-in note [:pullRequest :id]))
                                   (every? boolean? (map note [:viewerDidAuthor :viewerCanUpdate])))
                      (throw (ex-info "GitHub returned a note outside the selected pull request."
                                      {:code :feedback-identity-mismatch})))
                    {:ref (domain/contained-identity :github :note container (:id note))
                     :display-id (:id note) :body (:body note)
                     :author (when-let [author (:author note)]
                               {:ref (domain/contained-identity :github :user host (:id author))
                                :display-id (:login author)})
                     :created-at (:createdAt note) :updated-at (:updatedAt note) :system? false
                     :editable? (and (:viewerDidAuthor note) (:viewerCanUpdate note))
                     :position (select-keys note [:path :line :startLine :originalLine :originalStartLine :diffHunk])})
                  comments)}))

(defn get-feedback [repository reference]
  (let [live (feedback-repository) slug (:display-id repository) host (feedback-host live)
        _ (when-not (= slug (:display-id live))
            (throw (ex-info "GitHub feedback repository changed." {:code :feedback-identity-mismatch})))
        [owner name] (str/split slug #"/" 2)
        variables {:owner owner :name name :number (parse-long reference)}
        read-page #(get-in (graphql! host feedback-query %) [:repository])
        repo (read-page variables) pr (:pullRequest repo)]
    (when-not (and (= slug (:nameWithOwner repo)) (= (:url live) (:url repo))
                   (string? (:id repo)) (not (str/blank? (:id repo)))
                   (string? (:id pr)) (not (str/blank? (:id pr)))
                   (= reference (str (:number pr))) (not (str/blank? (:headRefOid pr))))
      (throw (ex-info "GitHub returned a different repository, pull request, or missing head."
                      {:code :feedback-identity-mismatch})))
    (let [page (fn [field cursor]
                 (let [next (read-page (assoc variables field cursor))]
                   (when-not (and (= (:id repo) (:id next)) (= (:id pr) (get-in next [:pullRequest :id]))
                                  (= (:headRefOid pr) (get-in next [:pullRequest :headRefOid])))
                     (throw (ex-info "GitHub feedback changed during pagination." {:code :stale-feedback-target})))
                   (:pullRequest next)))
          threads (feedback-pages (:reviewThreads pr) #(:reviewThreads (page :threads %)))
          reviewers (feedback-pages (:reviewRequests pr) #(:reviewRequests (page :reviewers %)))
          discussions (mapv #(normalize-feedback-thread host repo pr %) threads)]
      {:change-request (assoc (normalize-change-request repository pr)
                              :head-sha (:headRefOid pr)
                              :native-ref (domain/contained-identity :github :change-request
                                                                    (str host "/" (:id repo)) (:id pr)))
       :reviewers (mapv #(normalize-reviewer host (:requestedReviewer %)) reviewers)
       :discussions discussions
       :unresolved (mapv :ref (remove :resolved discussions))})))

(defn resolve-reviewer [reference]
  (let [host (feedback-host (feedback-repository))
        reviewer (:node (graphql! host (str "query Reviewer($id:ID!) { node(id:$id) { " reviewer-fields " } }")
                                 {:id reference}))]
    (when-not (and (= reference (:id reviewer)) (contains? #{"User" "Bot" "Team" "EnterpriseTeam"} (:__typename reviewer)))
      (throw (ex-info "GitHub reviewer ID does not identify a requestable reviewer." {:code :reviewer-not-found})))
    (normalize-reviewer host reviewer)))

(defn apply-feedback! [_repository change-request operation]
  (let [host (first (str/split (get-in change-request [:native-ref :container]) #"/" 2))
        id (get-in change-request [:native-ref :id])
        discussion (get-in operation [:discussion :ref :id])
        note (get-in operation [:note :ref :id])
        mutate (fn [name type input fields]
                 (get (graphql! host
                                (str "mutation FeedbackMutation($input:" type "!) { " name "(input:$input) { " fields " } }")
                                {:input input}) (keyword name)))]
    (case (:type operation)
      :reply (let [result (mutate "addPullRequestReviewThreadReply" "AddPullRequestReviewThreadReplyInput"
                                 {:pullRequestReviewThreadId discussion :body (:body operation)} "comment { id }")
                   created (get-in result [:comment :id])]
               (when (str/blank? created)
                 (throw (ex-info "GitHub did not return the reply ID." {:code :provider-response-invalid})))
               {:note-id created})
      :edit-note (let [result (mutate "updatePullRequestReviewComment" "UpdatePullRequestReviewCommentInput"
                                     {:pullRequestReviewCommentId note :body (:body operation)} "pullRequestReviewComment { id }")]
                   (when-not (= note (get-in result [:pullRequestReviewComment :id]))
                     (throw (ex-info "GitHub did not confirm the edited note." {:code :provider-response-invalid})))
                   {:note-id note})
      :resolve-discussion
      (let [name (if (:resolved operation) "resolveReviewThread" "unresolveReviewThread")
            type (if (:resolved operation) "ResolveReviewThreadInput" "UnresolveReviewThreadInput")
            result (mutate name type {:threadId discussion} "thread { id isResolved }")]
        (when-not (and (= discussion (get-in result [:thread :id]))
                       (= (:resolved operation) (get-in result [:thread :isResolved])))
          (throw (ex-info "GitHub did not confirm the thread resolution." {:code :provider-response-invalid})))
        {:discussion-id discussion})
      :update-reviewers
      (let [previous (set (map :ref (:previous-reviewers operation)))
            requested (if (:replace operation) (:reviewers operation)
                          (remove #(contains? previous (:ref %)) (:reviewers operation)))
            ids (fn [kind] (mapv #(get-in % [:ref :id]) (filter #(= kind (get-in % [:ref :kind])) requested)))
            result (mutate "requestReviews" "RequestReviewsInput"
                           {:pullRequestId id :userIds (ids :user) :teamIds (ids :team) :botIds (ids :bot)
                            :union (not (:replace operation))} "pullRequest { id }")]
        (when-not (= id (get-in result [:pullRequest :id]))
          (throw (ex-info "GitHub did not confirm the reviewer update." {:code :provider-response-invalid})))
        {:reviewer-ids (mapv #(get-in % [:ref :id]) (:reviewers operation))}))))

(defn assert-ready!
  "Authentication is checked by the existing gh CLI session on every call."
  [_app-config])

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

(defn neutral-adapter
  [_app-config]
  {:provider :github
   :capabilities capabilities
   :feedback-capabilities #{:reply :edit-note :resolve-discussion :update-reviewers}
   :get-feedback get-feedback
   :resolve-reviewer resolve-reviewer
   :apply-feedback! apply-feedback!
   :current-branch current-branch
   :maybe-current-change-request maybe-current-change-request
   :current-change-request current-change-request
   :current-repo current-repo
   :inspect-current inspect-current
   :identify-change-request identify-change-request
   :get-change-request get-change-request
   :update-change-request! update-change-request!
   :comment-change-request! comment-change-request!
   :create-change-request! create-change-request!
   :close-change-request! close-change-request!
   :prefix-change-request-title prefix-change-request-title})

(defn gh-authed?
  []
  (try (shell/run "gh" "auth" "status")
       true
       (catch Exception _ false)))

(defn setup
  [_app-config]
  (if (gh-authed?)
    (println "GitHub CLI: authenticated")
    (throw (ex-info "GitHub CLI is not authenticated. Run `gh auth login`, then re-run `ttt setup`." {:code :aborted})))
  nil)
