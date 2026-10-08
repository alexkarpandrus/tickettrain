(ns ttt.github-feedback-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.feedback-test-support :as support]
            [ttt.platform.feedback-journal :as journal]
            [ttt.platform.shell :as shell]
            [ttt.providers.forge.github :as github]))

(use-fixtures :each support/with-journal)

(def old-user {:__typename "User" :id "U_old" :login "existing" :name "Existing"})
(def new-user {:__typename "User" :id "U_new" :login "requested" :name "Requested"})
(def team {:__typename "Team" :id "T_keep" :slug "reviewers" :name "Reviewers" :organization {:login "org"}})

(defn native-note [id]
  {:id id :body "Original" :author {:id "U_me" :login "me"}
   :createdAt "2026-10-01" :updatedAt "2026-10-01" :viewerDidAuthor true :viewerCanUpdate true
   :pullRequest {:id "PR_7"} :path "file.clj" :line 7 :diffHunk "@@ diff"})

(defn native-thread [id note]
  {:id id :isResolved false :viewerCanReply true :viewerCanResolve true :viewerCanUnresolve true
   :pullRequest {:id "PR_7"} :notes [note]})

(defn state []
  {:repository (atom {:id "R_repo" :nameWithOwner "org/repo" :url "https://github.com/org/repo"})
   :pr (atom {:id "PR_7" :number 7 :title "Keep title" :body "Keep body" :url "https://github.com/org/repo/pull/7"
              :state "OPEN" :headRefOid "head-a" :headRefName "feature" :baseRefName "main"})
   :threads (atom [(native-thread "PRT_first" (native-note "PRRC_first"))
                   (native-thread "PRT_second" (native-note "PRRC_second"))])
   :reviewers (atom [old-user team]) :writes (atom []) :failure (atom nil) :calls (atom []) :outage (atom false)})

(defn connection [items cursor]
  (let [offset (if cursor (parse-long cursor) 0)
        nodes (vec (take 100 (drop offset items)))
        more? (< (+ offset (count nodes)) (count items))]
    {:nodes nodes :pageInfo {:hasNextPage more? :endCursor (when more? (str (+ offset (count nodes))))}}))

(defn native-input [state]
  (fn [input & args]
    (is (= ["gh" "api" "graphql" "--hostname"
            (.getAuthority (java.net.URI. (:url @(:repository state)))) "--input" "-"] args))
    (let [{:keys [query variables]} (json/parse-string input true)
          _ (swap! (:calls state) conj [query variables])
          thread-index (fn [id] (first (keep-indexed #(when (= id (:id %2)) %1) @(:threads state))))
          data (cond
                 (str/starts-with? query "query Feedback(")
                 (do (when @(:outage state) (throw (ex-info "Outage" {})))
                     (is (= {:owner "org" :name "repo" :number 7} (dissoc variables :threads :reviewers)))
                     {:repository
                      (assoc @(:repository state) :pullRequest
                             (assoc @(:pr state)
                                    :reviewRequests (connection (mapv #(hash-map :requestedReviewer %) @(:reviewers state))
                                                                (:reviewers variables))
                                    :reviewThreads (connection (mapv #(-> % (dissoc :notes)
                                                                         (assoc :comments (connection (:notes %) nil)))
                                                                    @(:threads state)) (:threads variables))))})
                 (str/starts-with? query "query FeedbackComments")
                 (let [thread (get @(:threads state) (thread-index (:id variables)))]
                   {:node (-> thread (dissoc :notes) (assoc :comments (connection (:notes thread) (:cursor variables))))})
                 (str/starts-with? query "query Reviewer")
                 {:node (get {"U_new" new-user "U_old" old-user "T_keep" team} (:id variables))}
                 :else
                 (let [[_ mutation] (re-find #"\{ ([a-zA-Z]+)\(input:" query)
                       fields (:input variables)
                       failure @(:failure state)
                       failing? (= mutation (:mutation failure))]
                   (swap! (:writes state) conj [mutation fields])
                   (when (and failing? (not (:accepted? failure)))
                     (reset! (:failure state) nil)
                     (throw (ex-info "Rejected" {:err (str "gh: rejected (HTTP " (:status failure) ")")})))
                   (let [result
                         (case mutation
                           "addPullRequestReviewThreadReply"
                           (let [i (thread-index (:pullRequestReviewThreadId fields))
                                 note (assoc (native-note "PRRC_created") :body (:body fields))]
                             (swap! (:threads state) update-in [i :notes] conj note)
                             {:comment {:id "PRRC_created"}})
                           "updatePullRequestReviewComment"
                           (let [target (:pullRequestReviewCommentId fields)]
                             (swap! (:threads state)
                                    #(mapv (fn [thread]
                                             (update thread :notes
                                                     (fn [notes] (mapv (fn [note] (if (= target (:id note))
                                                                                  (assoc note :body (:body fields) :updatedAt "2026-10-02") note)) notes)))) %))
                             {:pullRequestReviewComment {:id target}})
                           ("resolveReviewThread" "unresolveReviewThread")
                           (let [i (thread-index (:threadId fields)) resolved (= mutation "resolveReviewThread")]
                             (swap! (:threads state) assoc-in [i :isResolved] resolved)
                             {:thread {:id (:threadId fields) :isResolved resolved}})
                           "requestReviews"
                           (do (is (= "PR_7" (:pullRequestId fields)))
                               (is (= #{:pullRequestId :userIds :teamIds :botIds :union} (set (keys fields))))
                               (let [requested (mapv {"U_new" new-user "U_old" old-user "T_keep" team}
                                                     (concat (:userIds fields) (:teamIds fields) (:botIds fields)))]
                                 (swap! (:reviewers state) #(vec (distinct (if (:union fields) (concat % requested) requested)))))
                               {:pullRequest {:id "PR_7"}}))]
                     (when (and failing? (:accepted? failure))
                       (reset! (:failure state) nil)
                       (throw (ex-info "Lost response" {:err (str "gh: failed (HTTP " (:status failure) ")")})))
                     {(keyword mutation) result})))]
      (json/generate-string {:data data}))))

(defn with-github [state run]
  (with-redefs [config/load-config (fn [& _] {:forge {:provider :github}})
                shell/run (fn [& args]
                            (is (contains? #{["gh" "repo" "view" "--json" github/repo-fields]
                                             ["gh" "repo" "view" "--json" "nameWithOwner,url"]} (vec args)))
                            (json/generate-string (assoc @(:repository state) :defaultBranchRef {:name "main"})))
                shell/run-input (native-input state)]
    (run)))

(def reply {:type "reply" :discussion "PRT_second" :body "Fixed"})
(def edit {:type "edit_note" :discussion "PRT_second" :note "PRRC_second" :body "Corrected"})
(def resolve-thread {:type "resolve_discussion" :discussion "PRT_second" :resolved true})
(def reviewers {:type "update_reviewers" :reviewers ["U_new"]})

(deftest exact-approved-native-workflow-preserves-first-thread-teams-and-metadata
  (let [s (state) before @(:pr s) request (support/request [reply edit resolve-thread reviewers])]
    (with-github s
      #(let [preview (support/preview request)
             data (get-in preview [:envelope :data])
             id (:proposalId data)]
         (is (= 0 (:exit preview)) (pr-str preview))
         (is (= "github.com/R_repo" (get-in data [:changeRequest :nativeIdentity :container])))
         (is (= "PR_7" (get-in data [:changeRequest :nativeIdentity :id])))
         (is (= "PRRC_second" (get-in data [:operations 1 :note :identity :id])))
         (is (true? (get-in data [:operations 1 :note :editable])))
         (is (empty? @(:writes s)))
         (is (empty? (seq (.listFiles (journal/directory)))))
         (is (= 2 (:exit (support/apply-request (assoc-in request [:operations 0 :body] "Unapproved") id))))
         (is (empty? @(:writes s)))
         (let [result (support/apply-request request id)]
           (is (= 0 (:exit result)) (pr-str result))
           (is (= before @(:pr s)))
           (is (= [(native-note "PRRC_first")] (get-in @(:threads s) [0 :notes])))
           (is (= "Corrected" (get-in @(:threads s) [1 :notes 0 :body])))
           (is (= "Fixed" (get-in @(:threads s) [1 :notes 1 :body])))
           (is (get-in @(:threads s) [1 :isResolved]))
           (is (= ["U_old" "T_keep" "U_new"] (mapv :id @(:reviewers s))))
           (is (= ["PRT_first"] (mapv :id (get-in result [:envelope :data :feedback :unresolvedDiscussions]))))
           (let [writes @(:writes s)]
             (is (= id (support/proposal-id request)))
             (is (= 0 (:exit (support/apply-request request id))))
             (is (= writes @(:writes s)))))))))

(deftest reviewer-replacement-clear-and-thread-reopening-are-explicit
  (let [s (state)]
    (swap! (:threads s) assoc-in [1 :isResolved] true)
    (with-github s
      #(let [request (support/request [(assoc resolve-thread :resolved false)
                                       (assoc reviewers :replace true)])
             id (support/proposal-id request)]
         (is (= 0 (:exit (support/apply-request request id))))
         (is (= [new-user] @(:reviewers s)))
         (is (false? (get-in @(:threads s) [1 :isResolved])))
         (let [clear (assoc (support/request [{:type "update_reviewers" :reviewers [] :replace true}]) :batchId "clear")]
           (is (= 0 (:exit (support/apply-request clear (support/proposal-id clear)))))
           (is (empty? @(:reviewers s))))))))

(deftest inspection-paginates-threads-comments-and-reviewers
  (let [s (state)]
    (reset! (:threads s) (mapv #(native-thread (str "PRT_" %) (native-note (str "PRRC_" %))) (range 101)))
    (swap! (:threads s) assoc-in [100 :notes] (mapv #(native-note (str "PRRC_last_" %)) (range 101)))
    (reset! (:reviewers s) (mapv #(assoc old-user :id (str "U_" %)) (range 101)))
    (with-github s
      #(let [result (agent/run ["inspect" "--feedback" "--change-request" "7"])
             data (get-in result [:envelope :data :feedback])]
         (is (= 0 (:exit result)) (pr-str result))
         (is (= 101 (count (:discussions data))))
         (is (= 101 (count (get-in data [:discussions 100 :notes]))))
         (is (= 101 (count (:reviewers data))))
         (is (= 101 (count (:unresolvedDiscussions data))))))))

(deftest wrong-native-identities-and-permissions-fail-before-writing
  (doseq [change [(fn [s] (swap! (:repository s) assoc :nameWithOwner "other/repo"))
                  (fn [s] (swap! (:pr s) assoc :number 8))
                  (fn [s] (swap! (:threads s) assoc-in [1 :pullRequest :id] "PR_other"))
                  (fn [s] (swap! (:threads s) assoc-in [1 :notes 0 :pullRequest :id] "PR_other"))
                  (fn [s] (swap! (:threads s) assoc-in [1 :notes 0 :viewerDidAuthor] false))
                  (fn [s] (swap! (:threads s) assoc-in [1 :viewerCanReply] false))
                  (fn [s] (swap! (:threads s) assoc-in [1 :viewerCanResolve] false))]]
    (let [s (state)]
      (change s)
      (with-github s #(is (= 2 (:exit (support/preview (support/request [reply edit resolve-thread]))))))
      (is (empty? @(:writes s)))))
  (let [s (state)]
    (with-github s
      #(doseq [operations [[(assoc edit :note "PRRC_first")] [(assoc reviewers :reviewers ["not-a-user"])]]]
         (is (= 2 (:exit (support/preview (support/request operations)))))))
    (is (empty? @(:writes s)))))

(deftest head-native-target-and-permission-changes-invalidate-approval
  (doseq [change [(fn [s] (swap! (:pr s) assoc :headRefOid "head-b"))
                  (fn [s] (swap! (:repository s) assoc :id "R_replaced"))
                  (fn [s] (swap! (:repository s) assoc :url "https://enterprise.example/org/repo"))
                  (fn [s] (swap! (:threads s) assoc-in [1 :notes 0 :body] "Concurrent edit"))
                  (fn [s] (swap! (:threads s) assoc-in [1 :notes 0 :viewerCanUpdate] false))]]
    (let [s (state) request (support/request [edit])]
      (with-github s
        #(let [id (support/proposal-id request)]
           (change s)
           (is (= 2 (:exit (support/apply-request request id))))))
      (is (empty? @(:writes s))))))

(deftest confirmed-rejection-can-retry-without-repeating-successful-reply
  (let [s (state) request (support/request [reply edit])]
    (reset! (:failure s) {:mutation "updatePullRequestReviewComment" :status 403})
    (with-github s
      #(let [id (support/proposal-id request)
             failure (support/apply-request request id)]
         (is (= ["succeeded" "failed"] (mapv :status (get-in failure [:envelope :error :partialResult :operations]))))
         (is (= id (support/proposal-id request)))
         (is (= 0 (:exit (support/apply-request request id))))
         (is (= 1 (count (filter (fn [[name _]] (= name "addPullRequestReviewThreadReply")) @(:writes s)))))))))

(deftest ambiguous-replies-stop-retry-and-outages-retain-saved-outcomes
  (doseq [status [408 500]]
    (let [s (state) request (assoc (support/request [reply edit]) :batchId (str "status-" status))]
      (reset! (:failure s) {:mutation "addPullRequestReviewThreadReply" :status status :accepted? true})
      (with-github s
        #(let [id (support/proposal-id request)
               failure (support/apply-request request id)]
           (is (= ["unknown" "pending"] (mapv :status (get-in failure [:envelope :error :partialResult :operations]))))
           (is (= 2 (:exit (support/apply-request request id))))
           (is (= 1 (count @(:writes s))))))))
  (let [s (state) request (assoc (support/request [reply edit]) :batchId "outage")]
    (reset! (:failure s) {:mutation "updatePullRequestReviewComment" :status 403})
    (with-github s
      #(let [id (support/proposal-id request)]
         (support/apply-request request id)
         (reset! (:outage s) true)
         (is (= "PRRC_created" (get-in (support/apply-request request id)
                                      [:envelope :error :partialResult :operations 0 :affectedIds :noteId])))
         (is (= 2 (count @(:writes s))))))))

(deftest malformed-native-pages-and-graphql-errors-are-rejected
  (is (thrown? Exception (github/feedback-pages {:nodes nil :pageInfo {:hasNextPage false}} identity)))
  (is (thrown? Exception (github/feedback-pages {:nodes [] :pageInfo {:hasNextPage true :endCursor "repeat"}}
                                               (constantly {:nodes [] :pageInfo {:hasNextPage true :endCursor "repeat"}}))))
  (with-redefs [shell/run-input (fn [& _] "{\"data\":{\"mutation\":null},\"errors\":[{\"message\":\"uncertain\"}]}")]
    (is (= :provider-response-invalid
           (try (github/graphql! "github.com" "mutation" {}) (catch Exception ex (:code (ex-data ex))))))))

(deftest graphql-partial-data-with-http-200-errors-is-never-replayed
  (let [s (state) request (support/request [reply edit]) native (native-input s)]
    (with-github s
      #(with-redefs [shell/run-input
                     (fn [payload & args]
                       (let [response (apply native payload args)
                             query (:query (json/parse-string payload true))]
                         (if (str/includes? query "addPullRequestReviewThreadReply")
                           (json/generate-string (assoc (json/parse-string response true)
                                                        :errors [{:message "Partial mutation error"}]))
                           response)))]
         (let [id (support/proposal-id request) result (support/apply-request request id)]
           (is (= ["unknown" "pending"] (mapv :status (get-in result [:envelope :error :partialResult :operations]))))
           (is (= 2 (:exit (support/apply-request request id))))
           (is (= 1 (count @(:writes s))))
           (is (= 2 (count (get-in @(:threads s) [1 :notes])))))))))
