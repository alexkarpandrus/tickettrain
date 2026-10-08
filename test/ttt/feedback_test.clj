(ns ttt.feedback-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is use-fixtures]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.feedback :as feedback]
            [ttt.platform.feedback-journal :as journal]
            [ttt.providers.forge.gitlab :as gitlab]))

(use-fixtures :each
  (fn [run]
    (let [root (.toFile (java.nio.file.Files/createTempDirectory
                        "ttt-feedback-test-" (make-array java.nio.file.attribute.FileAttribute 0)))]
      (try (with-redefs [journal/directory (constantly root)] (run))
           (finally (doseq [file (reverse (file-seq root))] (.delete file)))))))

(def cfg {:forge {:provider :gitlab :token "test-only"}})
(def project {:id 17 :path_with_namespace "group/proj" :default_branch "main"})
(def old-reviewer {:id 1 :username "existing" :name "Existing" :state "active"})
(def new-reviewer {:id 2 :username "requested" :name "Requested" :state "active"})
(def mr-path "/projects/group%2Fproj/merge_requests/7")

(defn native-note [id]
  {:id id :body "Original review" :author old-reviewer :project_id 17
   :noteable_id 200 :noteable_type "MergeRequest" :system false
   :created_at "2026-10-01T10:00:00Z" :updated_at "2026-10-01T10:00:00Z"
   :resolvable true :resolved false :position {:head_sha "head-a" :new_path "file.clj" :new_line 7}})

(defn state []
  {:mr (atom {:id 200 :project_id 17 :iid 7 :sha "head-a" :state "opened"
              :title "Preserve title" :description "Preserve body" :labels ["keep"]
              :milestone {:id 4} :source_branch "feature" :target_branch "main"
              :reviewers [old-reviewer]})
   :discussions (atom [{:id "thread-one" :individual_note false :notes [(native-note 10)]}])
   :calls (atom []) :mutations (atom []) :failure (atom nil) :next-note (atom 1000)})

(defn native-api [state]
  (fn [_ method path query]
    (swap! (:calls state) conj [method path query])
    (if (= :get method)
      (cond
        (= "/projects/group%2Fproj" path) project
        (= mr-path path) @(:mr state)
        (= (str mr-path "/discussions") path)
        (vec (take (:per_page query) (drop (* (dec (:page query)) (:per_page query)) @(:discussions state))))
        (= "/users/1" path) old-reviewer
        (= "/users/2" path) new-reviewer
        :else (throw (ex-info "Unexpected GET" {:path path})))
      (do
        (swap! (:mutations state) conj [method path query])
        (when-let [failure @(:failure state)]
          (when (= path (:path failure))
            (reset! (:failure state) nil)
            (throw (ex-info "Simulated provider failure" (dissoc failure :path)))))
        (cond
          (and (= :post method) (= (str mr-path "/discussions/thread-one/notes") path))
          (let [note (assoc (native-note (swap! (:next-note state) inc)) :body (:body query))]
            (swap! (:discussions state) update-in [0 :notes] conj note)
            note)
          (and (= :put method) (= (str mr-path "/discussions/thread-one/notes/10") path))
          (do (swap! (:discussions state) update-in [0 :notes 0] assoc
                     :body (:body query) :updated_at "2026-10-02T10:00:00Z")
              (get-in @(:discussions state) [0 :notes 0]))
          (and (= :put method) (= (str mr-path "/discussions/thread-one") path))
          (do (swap! (:discussions state) update-in [0 :notes]
                     #(mapv (fn [note] (if (:resolvable note) (assoc note :resolved (:resolved query)) note)) %))
              (first @(:discussions state)))
          (and (= :put method) (= mr-path path))
          (swap! (:mr state) assoc :reviewers
                 (mapv {1 old-reviewer 2 new-reviewer} (:reviewer_ids query)))
          :else (throw (ex-info "Unexpected mutation" {:path path :query query})))))))

(defn with-gitlab [state run]
  (with-redefs [gitlab/remote-slug (constantly "group/proj")
                gitlab/api! (native-api state)
                config/load-config (fn [& _] cfg)]
    (run)))

(defn request [operations]
  {:action "review_change_request" :changeRequest "7" :batchId "batch-one"
   :expectedHead "head-a" :operations operations})

(def reply {:type "reply" :discussion "thread-one" :body "Fixed and verified"})
(def edit {:type "edit_note" :discussion "thread-one" :note "10" :body "Corrected note"})
(def resolve-thread {:type "resolve_discussion" :discussion "thread-one" :resolved true})
(def reviewers {:type "update_reviewers" :reviewers ["2"]})

(defn cli-preview [request]
  (agent/run ["preview" "--request" (json/generate-string request)]))
(defn cli-apply [request id]
  (agent/run ["apply" "--request" (json/generate-string request) "--approve" id]))
(defn proposal-id [request]
  (let [result (cli-preview request)]
    (is (true? (get-in result [:envelope :ok])) (pr-str result))
    (get-in result [:envelope :data :proposalId])))

(deftest preview-pins-native-targets-and-does-not-write
  (let [state (state) request (request [reply edit resolve-thread reviewers])]
    (with-gitlab state
      #(let [data (get-in (cli-preview request) [:envelope :data])]
         (is (= "group/proj" (get-in data [:changeRequest :identity :container])))
         (is (= "head-a" (get-in data [:changeRequest :headSha])))
         (is (= "thread-one" (get-in data [:operations 0 :discussion :identity :id])))
         (is (= "group/proj!7" (get-in data [:operations 1 :note :identity :container])))
         (is (= "10" (get-in data [:operations 1 :note :identity :id])))
         (is (= ["1" "2"] (mapv (fn [user] (get-in user [:identity :id])) (get-in data [:operations 3 :reviewers]))))
         (is (empty? @(:mutations state)))
         (is (empty? (seq (.listFiles (journal/directory)))))))))

(deftest exact-approval-replies-edits-resolves-and-preserves-mr-metadata
  (let [state (state) before @(:mr state) request (request [reply edit resolve-thread reviewers])]
    (with-gitlab state
      #(let [id (proposal-id request)
             stale (cli-apply (assoc-in request [:operations 0 :body] "Not approved") id)]
         (is (= "stale-proposal" (get-in stale [:envelope :error :code])))
         (is (empty? @(:mutations state)))
         (let [result (cli-apply request id)]
           (is (= 0 (:exit result)) (pr-str result))
           (is (= "1001" (get-in result [:envelope :data :operations 0 :affectedIds :noteId])))
           (is (= "Corrected note" (get-in @(:discussions state) [0 :notes 0 :body])))
           (is (= "Fixed and verified" (get-in @(:discussions state) [0 :notes 1 :body])))
           (is (every? :resolved (get-in @(:discussions state) [0 :notes])))
           (is (= (dissoc before :reviewers) (dissoc @(:mr state) :reviewers)))
           (is (= [1 2] (mapv :id (:reviewers @(:mr state)))))
           (is (= [] (get-in result [:envelope :data :feedback :unresolvedDiscussions]))))))))

(deftest completed-batch-retry-does-not-repeat-native-mutations
  (let [state (state) request (request [reply edit resolve-thread reviewers])]
    (with-gitlab state
      #(let [id (proposal-id request)]
         (is (= 0 (:exit (cli-apply request id))))
         (let [mutations @(:mutations state)]
           (is (= id (proposal-id request)))
           (is (= 0 (:exit (cli-apply request id))))
           (is (= mutations @(:mutations state)))
           (is (= 2 (count (get-in @(:discussions state) [0 :notes])))))))))

(deftest partial-failure-retry-skips-the-successful-reply
  (let [state (state) request (request [reply resolve-thread reviewers])]
    (reset! (:failure state) {:path (str mr-path "/discussions/thread-one") :status 403 :code :remote-api-error})
    (with-gitlab state
      #(let [id (proposal-id request) failed (cli-apply request id)]
         (is (= "feedback-partial-failure" (get-in failed [:envelope :error :code])))
         (is (= ["succeeded" "failed" "pending"]
                (mapv :status (get-in failed [:envelope :error :partialResult :operations]))))
         (is (= "1001" (get-in failed [:envelope :error :partialResult :operations 0 :affectedIds :noteId])))
         (is (= 1 (count (get-in failed [:envelope :error :partialResult :feedback :unresolvedDiscussions]))))
         (is (= id (proposal-id request)))
         (is (= 0 (:exit (cli-apply request id))))
         (is (= 1 (count (filter (fn [[method]] (= :post method)) @(:mutations state)))))))))

(deftest unknown-reply-outcome-stops-retries-without-duplicate-notes
  (let [state (state) request (request [reply resolve-thread])]
    (with-gitlab state
      #(let [api gitlab/api! id (proposal-id request)]
         (with-redefs [gitlab/api! (fn [cfg method path query]
                                    (let [result (api cfg method path query)]
                                      (when (= :post method)
                                        (throw (ex-info "Lost response after accepted reply" {:code :provider-unavailable})))
                                      result))]
           (is (= "unknown" (get-in (cli-apply request id)
                                    [:envelope :error :partialResult :operations 0 :status]))))
         (let [calls @(:mutations state) result (cli-apply request id)]
           (is (= "feedback-partial-failure" (get-in result [:envelope :error :code])))
           (is (= calls @(:mutations state)))
           (is (= 2 (count (get-in @(:discussions state) [0 :notes])))))))))

(deftest source-head-is-pinned-before-approval-and-again-before-write
  (let [state (state) request (request [reply])]
    (with-gitlab state
      #(let [id (proposal-id request)]
         (swap! (:mr state) assoc :sha "new-head")
         (is (= "stale-feedback-head" (get-in (cli-apply request id) [:envelope :error :code])))
         (is (empty? @(:mutations state)))))
    (swap! (:mr state) assoc :sha "head-a")
    (with-gitlab state
      #(let [id (proposal-id request) native gitlab/get-feedback calls (atom 0)]
         (with-redefs [gitlab/get-feedback (fn [& args]
                                            (when (= 2 (swap! calls inc)) (swap! (:mr state) assoc :sha "changed-before-write"))
                                            (apply native args))]
           (let [result (cli-apply request id)]
             (is (= "stale-feedback-head" (get-in result [:envelope :error :partialResult :operations 0 :error :code])))
             (is (empty? @(:mutations state)))))))))

(deftest wrong-project-mr-and-note-identities-fail-without-mutation
  (doseq [[field value] [[:project_id 99] [:iid 8]]]
    (let [state (state)]
      (swap! (:mr state) assoc field value)
      (with-gitlab state
        (fn []
          (is (= "feedback-identity-mismatch" (get-in (cli-preview (request [reply])) [:envelope :error :code])))
          (is (empty? @(:mutations state)))))))
  (let [state (state)]
    (swap! (:discussions state) assoc-in [0 :notes 0 :project_id] 99)
    (with-gitlab state
      (fn []
        (is (= "feedback-identity-mismatch" (get-in (cli-preview (request [edit])) [:envelope :error :code])))
        (is (empty? @(:mutations state)))))))

(deftest all-discussion-pages-and-native-note-metadata-are-returned
  (let [state (state)]
    (reset! (:discussions state)
            (mapv (fn [id] {:id (str "thread-" id) :individual_note false :notes [(native-note id)]}) (range 1 102)))
    (with-gitlab state
      #(let [result (agent/run ["inspect" "--feedback" "--change-request" "7"])
             data (get-in result [:envelope :data :feedback])]
         (is (= 0 (:exit result)))
         (is (= 101 (count (:discussions data))))
         (is (= 101 (count (:unresolvedDiscussions data))))
         (is (= "101" (get-in data [:discussions 100 :notes 0 :identity :id])))
         (is (= "2026-10-01T10:00:00Z" (get-in data [:discussions 100 :notes 0 :createdAt])))
         (is (= {:head_sha "head-a" :new_path "file.clj" :new_line 7}
                (get-in data [:discussions 100 :notes 0 :position])))
         (is (= [1 2] (mapv (fn [[_ _ query]] (:page query))
                            (filter (fn [[_ path]] (= (str mr-path "/discussions") path)) @(:calls state))))) ))))

(deftest non-resolvable-and-individual-discussions-are-rejected
  (doseq [[field value operation code] [[[:notes 0 :resolvable] false resolve-thread "discussion-not-resolvable"]
                                       [[:individual_note] true reply "discussion-not-threaded"]
                                       [[:notes 0 :system] true edit "note-not-editable"]]]
    (let [state (state)]
      (swap! (:discussions state) assoc-in (into [0] field) value)
      (with-gitlab state
        #(do (is (= code (get-in (cli-preview (request [operation])) [:envelope :error :code])))
             (is (empty? @(:mutations state))))))))

(deftest reviewer-replacement-and-discussion-reopening-require-explicit-values
  (let [state (state)]
    (with-gitlab state
      (fn []
        (doseq [operation [(assoc reviewers :replace true)
                           {:type "update_reviewers" :reviewers [] :replace true}
                           resolve-thread (assoc resolve-thread :resolved false)]]
          (let [request (assoc (request [operation]) :batchId (str (java.util.UUID/randomUUID)))]
            (is (= 0 (:exit (cli-apply request (proposal-id request))))))
          (when (= "update_reviewers" (:type operation))
            (is (= (mapv parse-long (:reviewers operation)) (mapv :id (:reviewers @(:mr state))))))
          (when (= "resolve_discussion" (:type operation))
            (is (= (:resolved operation) (get-in @(:discussions state) [0 :notes 0 :resolved])))))))))

(deftest recovery-does-not-accept-a-different-request-or-config
  (let [state (state) request (request [reply])]
    (with-gitlab state
      #(let [id (proposal-id request)]
         (is (= 0 (:exit (cli-apply request id))))
         (is (= "feedback-batch-mismatch"
                (get-in (cli-preview (assoc-in request [:operations 0 :body] "Changed")) [:envelope :error :code])))
         (with-redefs [config/load-config (fn [& _] (assoc cfg :profile :other))]
           (is (= "stale-proposal" (get-in (cli-apply request id) [:envelope :error :code]))))))))

(deftest optional-feedback-capabilities-are-reported-and-validated
  (let [version (agent/execute-command "version" [])]
    (is (= ["edit_note" "reply" "resolve_discussion" "update_reviewers"] (get-in version [:forgeFeedbackCapabilities "gitlab"])))
    (is (= [] (get-in version [:forgeFeedbackCapabilities "github"]))))
  (let [adapter (gitlab/neutral-adapter cfg)]
    (is (= adapter (adapters/assert-capabilities! :forge adapter)))
    (is (thrown? Exception (adapters/assert-capabilities! :forge (dissoc adapter :apply-feedback!))))
    (is (thrown? Exception (adapters/assert-capabilities! :forge (assoc adapter :feedback-capabilities #{:invented})))))
  (is (= :unsupported-forge-operation
         (try (feedback/preview {:forge {:provider :github :capabilities #{}}}
                                {:batch-id "test" :change-request-ref "7"})
              (catch Exception ex (:code (ex-data ex)))))))

(deftest invalid-feedback-input-is-rejected-at-the-json-boundary
  (doseq [invalid [(assoc (request [reply]) :batchId "../escape")
                   (assoc (request [reply]) :operations [])
                   (request [reply reply])
                   (request [(assoc edit :note 10)])
                   (request [(assoc resolve-thread :resolved "true")])
                   (request [(assoc reply :body nil)])
                   (request [{:type "update_reviewers" :reviewers []}])
                   (request [(assoc reviewers :replace "true")])
                   (request [(assoc reply :unexpected "field")])]]
    (is (= :invalid-request (try (agent/validate-request! invalid)
                                (catch Exception ex (:code (ex-data ex))))))))

(deftest journal-lock-and-corruption-fail-before-native-mutations
  (let [state (state) request (request [reply])]
    (with-gitlab state
      #(let [id (proposal-id request) lock (java.io.File. (journal/directory) "batch-one.lock")]
         (.mkdir lock)
         (is (= "feedback-batch-locked" (get-in (cli-apply request id) [:envelope :error :code])))
         (.delete lock)
         (spit (journal/path "batch-one") "{:version 1 :outcomes []}")
         (is (= "feedback-journal-invalid" (get-in (cli-preview request) [:envelope :error :code])))
         (is (empty? @(:mutations state)))))))


(deftest configured-repository-and-reviewer-identities-cannot-be-substituted
  (let [state (state)]
    (with-gitlab state
      (fn []
        (let [api gitlab/api!]
          (with-redefs [gitlab/api! (fn [cfg method path query]
                                     (if (= "/projects/group%2Fproj" path)
                                       (assoc project :path_with_namespace "other/project")
                                       (api cfg method path query)))]
            (is (= "feedback-identity-mismatch" (get-in (cli-preview (request [reply])) [:envelope :error :code]))))
          (with-redefs [gitlab/api! (fn [cfg method path query]
                                     (if (= "/users/2" path) (assoc new-reviewer :id 99)
                                         (api cfg method path query)))]
            (is (= "reviewer-not-found" (get-in (cli-preview (request [reviewers])) [:envelope :error :code])))))
        (is (empty? @(:mutations state)))))))

(deftest native-target-changes-after-approval-are-not-overwritten
  (doseq [operation [edit reviewers]]
    (let [state (state) request (assoc (request [operation]) :batchId (str "concurrent-" (:type operation)))]
      (with-gitlab state
        (fn []
          (let [id (proposal-id request) native gitlab/get-feedback calls (atom 0)]
            (with-redefs [gitlab/get-feedback (fn [& args]
                                               (when (= 2 (swap! calls inc))
                                                 (if (= "edit_note" (:type operation))
                                                   (swap! (:discussions state) assoc-in [0 :notes 0 :body] "Concurrent edit")
                                                   (swap! (:mr state) update :reviewers conj new-reviewer)))
                                               (apply native args))]
              (let [result (cli-apply request id)]
                (is (= "stale-feedback-target" (get-in result [:envelope :error :partialResult :operations 0 :error :code])))
                (is (empty? @(:mutations state)))))))))))

(deftest missing-final-readback-can-be-retried-without-another-reply
  (let [state (state) request (request [reply])]
    (with-gitlab state
      (fn []
        (let [id (proposal-id request) native gitlab/get-feedback calls (atom 0)]
          (with-redefs [gitlab/get-feedback (fn [& args]
                                             (when (= 3 (swap! calls inc))
                                               (throw (ex-info "Readback unavailable" {:code :provider-unavailable})))
                                             (apply native args))]
            (let [result (cli-apply request id)]
              (is (= "feedback-partial-failure" (get-in result [:envelope :error :code])))
              (is (= "succeeded" (get-in result [:envelope :error :partialResult :operations 0 :status])))
              (is (= "Readback unavailable" (get-in result [:envelope :error :partialResult :readbackError])))))
          (let [mutations @(:mutations state)]
            (is (= 0 (:exit (cli-apply request id))))
            (is (= mutations @(:mutations state)))))))))

(deftest final-readback-does-not-report-an-unapplied-resolution-as-complete
  (let [state (state) request (request [resolve-thread])]
    (with-gitlab state
      (fn []
        (let [id (proposal-id request) native gitlab/api!]
          (with-redefs [gitlab/api! (fn [cfg method path query]
                                     (if (= :put method) {} (native cfg method path query)))]
            (let [result (cli-apply request id)]
              (is (= "feedback-partial-failure" (get-in result [:envelope :error :code])))
              (is (= 1 (count (get-in result [:envelope :error :partialResult :feedback :unresolvedDiscussions]))))
              (is (string? (get-in result [:envelope :error :partialResult :readbackError]))))))))))

(deftest journal-failure-after-an-acknowledged-reply-preserves-the-known-result
  (let [state (state) request (request [reply resolve-thread])]
    (with-gitlab state
      (fn []
        (let [id (proposal-id request) write journal/write-batch!]
          (with-redefs [journal/write-batch! (fn [batch-id batch]
                                              (if (= :started (get-in batch [:outcomes 0 :status]))
                                                (write batch-id batch)
                                                (throw (ex-info "Journal disk full" {:code :feedback-journal-unavailable}))))]
            (let [result (cli-apply request id)]
              (is (= "feedback-partial-failure" (get-in result [:envelope :error :code])))
              (is (= "succeeded" (get-in result [:envelope :error :partialResult :operations 0 :status])))
              (is (= "1001" (get-in result [:envelope :error :partialResult :operations 0 :affectedIds :noteId])))
              (is (= "Journal disk full" (get-in result [:envelope :error :partialResult :recoveryError])))))
          (let [mutations @(:mutations state)]
            (is (= "started" (get-in (cli-apply request id) [:envelope :error :partialResult :operations 0 :status])))
            (is (= mutations @(:mutations state)))))))))

(deftest journal-is-owner-only-and-records-started-before-the-native-write
  (let [state (state) request (request [reply])]
    (with-gitlab state
      (fn []
        (let [id (proposal-id request) native gitlab/api!
              sync journal/sync-directory! synced (atom [])]
          (with-redefs [journal/sync-directory! (fn [directory]
                                                 (swap! synced conj directory)
                                                 (sync directory))
                        gitlab/api! (fn [cfg method path query]
                                     (when (= :post method)
                                       (is (= [(.getParentFile (.getAbsoluteFile (journal/directory)))
                                               (journal/directory)] @synced))
                                       (is (= :started (get-in (journal/read-batch "batch-one") [:outcomes 0 :status]))))
                                     (native cfg method path query))]
            (is (= 0 (:exit (cli-apply request id)))))
          (is (= #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
                   java.nio.file.attribute.PosixFilePermission/OWNER_WRITE}
                 (set (java.nio.file.Files/getPosixFilePermissions
                       (.toPath (journal/path "batch-one"))
                       (make-array java.nio.file.LinkOption 0))))))))))


(deftest journal-directory-sync-failure-stops-before-native-mutations
  (let [state (state) request (request [reply])]
    (with-gitlab state
      #(let [id (proposal-id request)]
         (with-redefs [journal/sync-directory! (fn [_]
                                                (throw (ex-info "Directory sync failed"
                                                                {:code :feedback-journal-unavailable})))]
           (is (= "feedback-journal-unavailable" (get-in (cli-apply request id) [:envelope :error :code])))
           (is (empty? @(:mutations state))))))))

(deftest repeated-reviewer-identities-are-normalized-to-one-assignment
  (let [state (state) request (request [(assoc reviewers :reviewers ["1" "2" "1"])])]
    (with-gitlab state
      (fn []
        (is (= 0 (:exit (cli-apply request (proposal-id request)))))
        (is (= [1 2] (mapv :id (:reviewers @(:mr state)))))))))
