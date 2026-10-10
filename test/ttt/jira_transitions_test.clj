(ns ttt.jira-transitions-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.cli.agent :as agent]
            [ttt.feedback-test-support :as support]
            [ttt.platform.feedback-journal :as feedback-journal]
            [ttt.platform.transition-journal :as journal]
            [ttt.platform.journal :as storage]
            [ttt.providers.tracker.jira :as jira]))

(use-fixtures :each
  (fn [run]
    (support/with-journal #(with-redefs [journal/directory feedback-journal/directory] (run)))))

(deftest journal-owner-permissions-are-enforced-not-assumed
  (doseq [directory? [false true]]
    (let [file (java.io.File. (journal/directory) (if directory? "permissions-dir" "permissions-file"))]
      (if directory? (.mkdir file) (.createNewFile file))
      (java.nio.file.Files/setPosixFilePermissions
       (.toPath file) (java.nio.file.attribute.PosixFilePermissions/fromString
                      (if directory? "rwxr-xr-x" "rw-r--r--")))
      (storage/owner-only! file directory?)
      (is (= (java.nio.file.attribute.PosixFilePermissions/fromString
              (if directory? "rwx------" "rw-------"))
             (java.nio.file.Files/getPosixFilePermissions
              (.toPath file) (make-array java.nio.file.LinkOption 0))))))
  (let [target (java.io.File. (journal/directory) "symlink-target")
        link (java.io.File. (journal/directory) "permissions-link")
        permissions (java.nio.file.attribute.PosixFilePermissions/fromString "rw-r--r--")]
    (.createNewFile target)
    (java.nio.file.Files/setPosixFilePermissions (.toPath target) permissions)
    (java.nio.file.Files/createSymbolicLink (.toPath link) (.toPath target)
                                           (make-array java.nio.file.attribute.FileAttribute 0))
    (is (= :journal-unavailable (try (storage/owner-only! link false) nil
                                     (catch Exception ex (:code (ex-data ex))))))
    (is (= permissions (java.nio.file.Files/getPosixFilePermissions
                        (.toPath target) (make-array java.nio.file.LinkOption 0))))))

(def config {:tracker {:provider :jira :site-url "https://acme.atlassian.net"
                       :cloud-id "cloud-1" :target-state "Done"}})
(def progress {:id "3" :name "In Progress" :statusCategory {:key "indeterminate"}})
(def review {:id "4" :name "Review" :statusCategory {:key "indeterminate"}})
(def issue {:id "10001" :key "APP-1"
            :self "https://acme.atlassian.net/rest/api/3/issue/10001"
            :fields {:summary "Test handoff" :status progress
                     :description "Keep this description" :labels ["keep"]
                     :issuelinks [] :customfield_123 [{:id "red"}]}})
(def transitions [{:id "21" :name "Submit for review" :to review :hasScreen true
                   :fields {:customfield_123 {:required true :name "Exchanges"
                                             :operations ["set"] :hasDefaultValue false}}}
                  {:id "22" :name "Another review" :to (assoc review :id "5") :fields {}}])

(defn error-data [run]
  (try (run) nil (catch Exception ex (ex-data ex))))

(defn inspect-with [selected available reference]
  (with-redefs [jira/api! (fn [_ method path query]
                           (is (= :get method))
                           (if (= path "/issue/10001/transitions")
                             (do (is (= {:expand "transitions.fields"} query))
                                 {:transitions available})
                             selected))]
    (jira/get-item-transitions config reference)))

(deftest native-inspection-distinguishes-same-category-and-same-name-destinations
  (let [result (inspect-with issue transitions "APP-1")
        item (:item result)]
    (is (= "active" (:state item)))
    (is (= {:id "3" :name "In Progress"} (:native-status item)))
    (is (= "10001" (get-in item [:native-ref :id])))
    (is (= "https://acme.atlassian.net" (get-in item [:native-ref :container])))
    (is (= {:siteUrl "https://acme.atlassian.net" :cloudId "cloud-1"} (:native-context result)))
    (is (= ["21" "22"] (mapv :id (:transitions result))))
    (is (= ["4" "5"] (mapv #(get-in % [:to :id]) (:transitions result))))
    (is (= ["active" "active"] (mapv #(get-in % [:to :state]) (:transitions result))))
    (is (= ["customfield_123"] (get-in result [:transitions 0 :requiredFields])))
    (is (= (get-in transitions [0 :fields]) (get-in result [:transitions 0 :fields])))))

(deftest gateway-native-identity-verifies-the-cloud-site-before-approval
  (let [selected (assoc issue :self (jira/api-endpoint config "/issue/10001"))
        metadata (atom {:baseUrl "https://acme.atlassian.net/"})
        calls (atom [])]
    (with-redefs [jira/api! (fn [_ method path _]
                             (swap! calls conj {:method method :path path})
                             (is (= :get method))
                             (case path
                               "/serverInfo" (if (instance? Exception @metadata) (throw @metadata) @metadata)
                               "/issue/10001/transitions" {:transitions transitions}
                               "/issue/APP-1" selected))]
      (let [a (:item (jira/get-item-transitions config "APP-1"))
            b (:item (jira/get-item-transitions
                      (assoc-in config [:tracker :site-url] "https://acme.atlassian.net/") "APP-1"))]
        (is (= (:native-ref a) (:native-ref b)))
        (is (= (storage/target-lock-name (:native-ref a)) (storage/target-lock-name (:native-ref b)))))
      (is (= :native-item-mismatch
             (:code (error-data #(jira/get-item-transitions
                                 (assoc-in config [:tracker :site-url] "https://other.atlassian.net") "APP-1")))))
      (doseq [invalid [{:baseUrl "https://other.atlassian.net"} {} {:baseUrl 42} nil]]
        (reset! metadata invalid)
        (is (= :native-item-mismatch (:code (error-data #(jira/get-item-transitions config "APP-1"))))))
      (reset! metadata (ex-info "Site metadata denied" {:code :remote-api-error :status 403}))
      (is (= 403 (:status (error-data #(jira/get-item-transitions config "APP-1")))))
      (is (empty? (filter #(not= :get (:method %)) @calls))))))

(deftest ordinary-jira-reads-retain-native-status-separately
  (doseq [normalize [jira/normalize-item jira/normalize-item-summary]]
    (let [item (normalize "https://acme.atlassian.net" issue)
          wire (agent/wire-entity item)]
      (is (= "active" (:state wire)))
      (is (= {:id "3" :name "In Progress"} (:nativeStatus wire)))
      (is (= "10001" (get-in wire [:nativeIdentity :id]))))))

(deftest inspection-fails-closed-on-wrong-identity-or-malformed-metadata
  (doseq [selected [(assoc issue :id "10002")
                    (assoc issue :key "APP-2")
                    (assoc issue :self "https://other.atlassian.net/rest/api/3/issue/10001")
                    (assoc issue :self "https://acme.atlassian.net.evil/rest/api/3/issue/10001")
                    (update-in issue [:fields :status] dissoc :id)]]
    (is (= :native-item-mismatch
           (:code (error-data #(inspect-with selected transitions "APP-1"))))))
  (doseq [available [nil {} (conj transitions (first transitions))
                     [(dissoc (first transitions) :to)]
                     [(dissoc (first transitions) :fields)]
                     [(assoc-in (first transitions) [:fields :customfield_123] {})]]]
    (is (= :invalid-transition-metadata
           (:code (error-data #(inspect-with issue available "APP-1")))))))

(deftest exact-item-reference-never-falls-back-to-search
  (with-redefs [jira/api! (fn [& _] (throw (Exception. "Must not request fuzzy input")))]
    (doseq [reference ["Test handoff" "" "app-1" "0" 10001 nil]]
      (is (= :invalid-item-reference
             (:code (error-data #(jira/get-item-transitions config reference)))))))
  (is (= "10001" (get-in (inspect-with issue transitions "10001") [:item :native-ref :id])))
  (is (= [] (:transitions (inspect-with issue [(assoc (first transitions) :isAvailable false)] "APP-1")))))

(deftest explicit-item-cli-inspection-is-tracker-only-and-capability-gated
  (with-redefs [agent/tracker-runtime (fn [_] {:tracker {:capabilities #{:get-item-transitions}
                                                        :get-item-transitions #(inspect-with issue transitions %)}})
                agent/forge-runtime (fn [_] (throw (Exception. "Forge must not load")))]
    (let [result (agent/execute-command "inspect" ["--item" "APP-1"])]
      (is (= {:id "3" :name "In Progress"} (get-in result [:item :nativeStatus])))
      (is (= "21" (get-in result [:transitions 0 :id]))))
    (is (= :invalid-request
           (:code (error-data #(agent/execute-command "inspect" ["--item" "APP-1" "--feedback"]))))))
  (with-redefs [agent/tracker-runtime (constantly {:tracker {:capabilities #{}}})]
    (is (= :unsupported-tracker-operation
           (:code (error-data #(agent/execute-command "inspect" ["--item" "APP-1"])))))))

(def request {:action "transition_item" :item "APP-1" :transitionId "21"
              :batchId "exact-handoff" :fields {:customfield_123 [{:id "blue"}]}})

(defn runtime [app-config]
  {:config app-config :tracker (jira/neutral-adapter app-config)})

(defn api-stub [state calls available]
  (fn [_ method path payload]
    (swap! calls conj {:method method :path path :payload payload})
    (case method
      :get (cond
             (= path (str "/issue/" (:id @state) "/transitions")) {:transitions @available}
             (contains? #{"/issue/APP-1" (str "/issue/" (:id @state))} path) @state
             (= path "/mypermissions") {:permissions {:ADD_COMMENTS {:havePermission true}}}
             (= path (str "/issue/" (:id @state) "/comment"))
             {:startAt (:startAt payload) :total (count (:comments @state)) :comments (vec (:comments @state))}
             :else (throw (ex-info (str "Unexpected GET " path) {})))
      :post (cond
              (= path (str "/issue/" (:id @state) "/transitions"))
              (do (swap! state update :fields merge
                         (assoc (:fields payload) :status
                                (:to (first (filter #(= (get-in payload [:transition :id]) (:id %)) @available)))))
                  nil)
              (= path (str "/issue/" (:id @state) "/comment"))
              (let [id (str (+ 30001 (count (:comments @state))))
                    comment {:id id :body (:body payload)
                             :self (str "https://acme.atlassian.net/rest/api/3/issue/" (:id @state) "/comment/" id)}]
                (swap! state update :comments (fnil conj []) comment)
                comment)
              :else (throw (ex-info (str "Unexpected POST " path) {})))
      (throw (ex-info "Unrelated native mutation" {})))))

(defn writes [calls] (filter #(not= :get (:method %)) @calls))

(deftest exact-transition-preview-apply-preserves-native-values-and-unrelated-content
  (let [state (atom issue) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [preview (agent/preview-data (runtime config) request)]
        (is (= "21" (get-in preview [:transition :id])))
        (is (= "4" (get-in preview [:transition :to :id])))
        (is (= "3" (get-in preview [:item :nativeStatus :id])))
        (is (= (:fields request) (:fields preview)))
        (is (= (:fields request) (get-in preview [:request :fields])))
        (is (empty? (writes calls)))
        (let [result (agent/apply-data! (runtime config) request (:proposalId preview))]
          (is (= "active" (get-in result [:item :state])))
          (is (= {:id "4" :name "Review"} (get-in result [:item :nativeStatus])))
          (is (= "succeeded" (get-in result [:operations 0 :status])))
          (is (= {:itemId "10001" :transitionId "21"} (get-in result [:operations 0 :affectedIds])))
          (is (= [{:method :post :path "/issue/10001/transitions"
                   :payload {:transition {:id "21"} :fields (:fields request)}}]
                 (vec (writes calls))))
          (is (= (select-keys (:fields issue) [:description :labels :issuelinks])
                 (select-keys (:fields @state) [:description :labels :issuelinks]))))))))

(deftest changed-approved-values-or-native-context-stop-before-any-write
  (doseq [change [:fields :site :cloud :issue :status :destination :required-current-value :unavailable]]
    (let [state (atom issue) calls (atom []) available (atom transitions)]
      (with-redefs [jira/api! (api-stub state calls available)]
        (let [preview (agent/preview-data (runtime config) request)
              changed-request (if (= change :fields) (assoc-in request [:fields :customfield_123 0 :id] "changed") request)
              changed-config (case change
                               :site (assoc-in config [:tracker :site-url] "https://other.atlassian.net")
                               :cloud (assoc-in config [:tracker :cloud-id] "other-cloud")
                               config)]
          (case change
            :issue (swap! state assoc :id "10002" :self "https://acme.atlassian.net/rest/api/3/issue/10002")
            :status (swap! state assoc-in [:fields :status] review)
            :destination (swap! available assoc-in [0 :to :id] "5")
            :required-current-value (swap! state assoc-in [:fields :customfield_123] [{:id "changed"}])
            :unavailable (reset! available [(second transitions)])
            nil)
          (is (contains? #{:stale-proposal :native-item-mismatch :transition-unavailable}
                         (:code (error-data #(agent/apply-data! (runtime changed-config) changed-request (:proposalId preview))))))
          (is (empty? (writes calls))))))))

(deftest native-write-rechecks-status-after-cli-approval-preflight
  (let [state (atom issue) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [proposal (agent/preview-proposal (runtime config) request)]
        (swap! state assoc-in [:fields :status] review)
        (is (= :stale-transition-target
               (:code (error-data #(jira/apply-item-transition-operation! config proposal (first (:operations proposal)))))))
        (is (empty? (writes calls)))))))

(deftest required-and-writable-transition-fields-are-validated-without-coercion
  (let [state (atom (update issue :fields dissoc :customfield_123)) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (doseq [fields [{} {:customfield_123 nil} {:customfield_123 []} {:customfield_123 {}}]]
        (is (= :transition-fields-required
               (:code (error-data #(agent/preview-data (runtime config) (assoc request :fields fields)))))))
      (doseq [fields [{:description "overwrite"} {:transition {:id "22"}}]]
        (is (= :unsupported-transition-field
               (:code (error-data #(agent/preview-data (runtime config) (assoc request :fields fields)))))))
      (doseq [value [false 0 [{:id "one"} {:id "two"}] {:id "nested"}]]
        (is (= value (get-in (agent/preview-data (runtime config) (assoc request :fields {:customfield_123 value}))
                            [:fields :customfield_123]))))
      (swap! available assoc-in [0 :fields :customfield_123 :hasDefaultValue] true)
      (is (= {} (:fields (agent/preview-data (runtime config) (dissoc request :fields)))))
      (is (= :transition-fields-required
             (:code (error-data #(agent/preview-data (runtime config) (assoc request :fields {:customfield_123 nil})))))))
    (is (empty? (writes calls)))))

(deftest transition-request-shape-and-unsupported-provider-reject-before-mutation
  (doseq [bad [(dissoc request :item) (dissoc request :transitionId) (dissoc request :batchId)
               (assoc request :fields nil) (assoc request :batchId "../escape")
               (assoc request :state "active") (assoc request :comment "")]]
    (is (= :invalid-request (:code (error-data #(agent/validate-request! bad))))))
  (is (= :unsupported-tracker-operation
         (:code (error-data #(agent/preview-data {:config {} :tracker {:capabilities #{}}} request)))))
  (with-redefs [agent/tracker-runtime (fn [_] (runtime config))
                agent/runtime (fn [_] (throw (Exception. "Do not load a forge")))]
    (is (= :jira (get-in (agent/request-runtime {} request) [:tracker :provider])))))

(deftest server-rejection-and-unknown-write-have-actionable-native-readback
  (doseq [status [400 403 408 499 500 nil]]
    (let [state (atom issue) calls (atom []) available (atom transitions)
          stub (api-stub state calls available)]
      (with-redefs [jira/api! (fn [config method path payload]
                               (if (= :post method)
                                 (do (swap! calls conj {:method method :path path :payload payload})
                                     (when (contains? #{408 499 500 nil} status)
                                       (swap! state assoc-in [:fields :status] review))
                                     (throw (ex-info "Field Exchanges must have value or the outcome is unknown"
                                                     {:code :remote-api-error :status status})))
                                 (stub config method path payload)))]
        (let [request (assoc request :batchId (str "server-" (or status "lost")))
              preview (agent/preview-data (runtime config) request)
              failure (error-data #(agent/apply-data! (runtime config) request (:proposalId preview)))
              result (:partial-result failure)]
          (is (= :item-transition-partial-failure (:code failure)))
          (is (= (if (contains? #{400 403} status) :failed :unknown) (get-in result [:outcomes 0 :status])))
          (is (= (if (contains? #{400 403} status) "3" "4") (get-in result [:readback :item :native-status :id])))
          (is (= 1 (count (writes calls))))
          (is (= :get (:method (last @calls))))
          (when-not (contains? #{400 403} status)
            (is (= :item-transition-partial-failure
                   (:code (error-data #(agent/apply-data! (runtime config) request (:proposalId preview))))))
            (is (= 1 (count (writes calls)))))
          (is (str/includes? (get-in (agent/failure "apply" (ex-info "Partial" {:partial-result result}))
                                    [:error :partialResult :operations 0 :error :message]) "Exchanges")))))))

(def handoff (assoc request :comment "Testing: bb test passed."))

(deftest approved-handoff-checkpoints-comments-and-transitions-and-never-repeats-success
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (when (= :post method)
                               (let [batch (journal/read-batch (:batchId handoff))
                                     index (if (str/ends-with? path "/comment") 0 1)]
                                 (is (= :started (get-in batch [:outcomes index :status])))))
                             (stub config method path payload))]
      (let [preview (agent/preview-data (runtime config) handoff)
            result (agent/apply-data! (runtime config) handoff (:proposalId preview))]
        (is (= ["comment" "transition"] (mapv :type (:operations preview))))
        (is (= ["succeeded" "succeeded"] (mapv :status (:operations result))))
        (is (= {:itemId "10001" :commentId "30001"} (get-in result [:operations 0 :affectedIds])))
        (is (= ["30001"] (mapv :id (get-in result [:readback :comments]))))
        (is (= 2 (count (writes calls))))
        (reset! available [])
        (is (= (:proposalId preview) (:proposalId (agent/preview-data (runtime config) handoff))))
        (is (= "4" (get-in (agent/apply-data! (runtime config) handoff (:proposalId preview)) [:item :nativeStatus :id])))
        (is (= 2 (count (writes calls))))
        (is (= #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
                 java.nio.file.attribute.PosixFilePermission/OWNER_WRITE}
               (set (java.nio.file.Files/getPosixFilePermissions (.toPath (journal/path (:batchId handoff)))
                                                                (make-array java.nio.file.LinkOption 0)))))))))

(deftest partial-handoff-retries-only-a-confirmed-rejected-transition
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available) reject? (atom true)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (cond
                               (and @reject? (= :post method) (str/ends-with? path "/transitions"))
                               (do (swap! calls conj {:method method :path path})
                                   (throw (ex-info "Jira validator: Exchanges must have value"
                                                   {:code :remote-api-error :status 400})))
                               (and (not @reject?) (= path "/mypermissions"))
                               (throw (Exception. "Do not recheck Add comments for a completed comment"))
                               :else (stub config method path payload)))]
      (let [preview (agent/preview-data (runtime config) handoff)
            partial (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))]
        (is (= [:succeeded :failed] (mapv :status (:outcomes partial))))
        (is (= "30001" (get-in partial [:outcomes 0 :result :comment-id])))
        (is (= "3" (get-in partial [:readback :item :native-status :id])))
        (reset! reject? false)
        (is (= ["succeeded" "succeeded"]
               (mapv :status (:operations (agent/apply-data! (runtime config) handoff (:proposalId preview))))))
        (is (= 1 (count (filter #(str/ends-with? (:path %) "/comment") (writes calls)))))
        (is (= 3 (count (writes calls))))))))

(deftest unknown-comment-is-inspected-but-equal-text-never-establishes-ownership
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (let [result (stub config method path payload)]
                               (when (= :post method) (throw (java.io.IOException. "Response lost")))
                               result))]
      (let [preview (agent/preview-data (runtime config) handoff)
            partial (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))]
        (is (= [:unknown :pending] (mapv :status (:outcomes partial))))
        (is (= (:comment handoff) (get-in partial [:readback :comments 0 :body])))
        (is (= "3" (get-in partial [:readback :item :native-status :id])))
        (is (= :item-transition-partial-failure
               (:code (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))))
        (is (= 1 (count (writes calls))))
        (is (some #(str/ends-with? (:path %) "/comment") (filter #(= :get (:method %)) @calls)))))))

(deftest unknown-transition-is-not-replayed-even-when-native-destination-is-observed
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (let [result (stub config method path payload)]
                               (when (and (= :post method) (str/ends-with? path "/transitions"))
                                 (reset! available [])
                                 (throw (java.io.IOException. "Transition response lost")))
                               result))]
      (let [preview (agent/preview-data (runtime config) handoff)
            partial (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))]
        (is (= [:succeeded :unknown] (mapv :status (:outcomes partial))))
        (is (= "4" (get-in partial [:readback :item :native-status :id])))
        (is (= :item-transition-partial-failure
               (:code (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))))
        (is (= 2 (count (writes calls))))))))

(deftest interrupted-started-records-remain-uncertain-and-require-native-inspection
  (let [state (atom issue) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [proposal (agent/preview-proposal (runtime config) handoff)]
        (journal/with-batch-lock (:batchId handoff)
          #(journal/write-batch! (:batchId handoff)
                                {:version 1 :proposal proposal :outcomes [{:status :started} {:status :pending}]}))
        (let [partial (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposal-id proposal))))]
          (is (= [:started :pending] (mapv :status (:outcomes partial))))
          (is (:readback partial))
          (is (empty? (writes calls))))))))

(deftest acknowledged-comment-ids-survive-checkpoint-and-native-readback-failures
  (let [state (atom issue) calls (atom []) available (atom transitions)
        original journal/write-batch! checkpoints (atom 0)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [preview (agent/preview-data (runtime config) handoff)
            partial (with-redefs [journal/write-batch! (fn [id batch]
                                                        (if (= 1 (swap! checkpoints inc))
                                                          (original id batch)
                                                          (throw (java.io.IOException. "Disk unavailable"))))]
                      (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview)))))]
        (is (= :succeeded (get-in partial [:outcomes 0 :status])))
        (is (= "30001" (get-in partial [:outcomes 0 :result :comment-id])))
        (is (:recovery-error partial))
        (is (= 1 (count (writes calls))))
        (is (= :started (get-in (journal/read-batch (:batchId handoff)) [:outcomes 0 :status])))
        (is (= :item-transition-partial-failure
               (:code (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))))
        (is (= 1 (count (writes calls))))))))

(deftest failed-prewrite-checkpoint-and-directory-sync-prevent-all-native-writes
  (let [state (atom issue) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [preview (agent/preview-data (runtime config) request)]
        (with-redefs [journal/write-batch! (fn [& _] (throw (java.io.IOException. "No checkpoint")))]
          (let [partial (:partial-result (error-data #(agent/apply-data! (runtime config) request (:proposalId preview))))]
            (is (= :failed (get-in partial [:outcomes 0 :status])))
            (is (:recovery-error partial))))
        (with-redefs [storage/owner-only! (fn [& _] (throw (java.io.IOException. "Permissions unavailable")))]
          (is (= :item-transition-journal-unavailable
                 (:code (error-data #(agent/apply-data! (runtime config) request (:proposalId preview)))))))
        (with-redefs [storage/sync-directory! (fn [_] (throw (java.io.IOException. "No directory sync")))]
          (is (= :item-transition-journal-unavailable
                 (:code (error-data #(agent/apply-data! (runtime config) request (:proposalId preview)))))))
        (is (empty? (writes calls)))))))

(deftest batch-and-native-target-locks-stop-competing-applies-before-writes
  (let [state (atom issue) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [proposal (agent/preview-proposal (runtime config) request)]
        (journal/with-batch-lock (:batchId request)
          #(is (= :item-transition-batch-locked
                  (:code (error-data (fn [] (agent/apply-data! (runtime config) request (:proposal-id proposal))))))))
        (journal/with-target-lock (:item proposal)
          #(let [other (assoc request :batchId "other-batch")
                 preview (agent/preview-data (runtime config) other)]
             (is (= :item-transition-batch-locked
                    (:code (error-data (fn [] (agent/apply-data! (runtime config) other (:proposalId preview)))))))))
        (is (empty? (writes calls)))))))

(deftest recovery-binds-request-profile-config-and-the-original-proposal-hash
  (let [state (atom issue) calls (atom []) available (atom transitions)]
    (with-redefs [jira/api! (api-stub state calls available)]
      (let [preview (agent/preview-data (runtime config) handoff)]
        (agent/apply-data! (runtime config) handoff (:proposalId preview))
        (doseq [[cfg req] [[config (assoc handoff :comment "Different testing comment")]
                          [(assoc config :profile :other) handoff]
                          [(assoc-in config [:tracker :cloud-id] "other-cloud") handoff]]]
          (is (= :item-transition-batch-mismatch
                 (:code (error-data #(agent/apply-data! (runtime cfg) req (:proposalId preview)))))))
        (let [batch (journal/read-batch (:batchId handoff))]
          (spit (journal/path (:batchId handoff))
                (pr-str (assoc-in batch [:proposal :operations 0 :body] "Unapproved text"))))
        (is (= :item-transition-journal-invalid
               (:code (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))))
        (is (= 2 (count (writes calls))))))))

(deftest comment-permission-and-readback-errors-remain-actionable-and-preserve-outcomes
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (if (= path "/mypermissions")
                               {:permissions {:ADD_COMMENTS {:havePermission false}}}
                               (stub config method path payload)))]
      (is (= :item-comment-permission-denied
             (:code (error-data #(agent/preview-data (runtime config) handoff)))))
      (is (empty? (writes calls))))
    (with-redefs [jira/api! stub]
      (let [preview (agent/preview-data (runtime config) handoff)]
        (agent/apply-data! (runtime config) handoff (:proposalId preview))
        (with-redefs [jira/api! (fn [& _] (throw (ex-info "Native read denied" {:status 403})))]
          (let [partial (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))]
            (is (= [:succeeded :succeeded] (mapv :status (:outcomes partial))))
            (is (= "30001" (get-in partial [:outcomes 0 :result :comment-id])))
            (is (:readback-error partial))))
        (is (= 2 (count (writes calls))))))))

(deftest native-comments-are-paginated-and-malformed-pages-fail-closed
  (let [body (jira/text->adf (:comment handoff))
        comments (mapv (fn [id] {:id id :body body
                                 :self (str "https://acme.atlassian.net/rest/api/3/issue/10001/comment/" id)})
                       ["30001" "30002" "30003"])
        state (atom (assoc issue :comments comments)) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (if (= path "/issue/10001/comment")
                               (let [start (:startAt payload)]
                                 {:startAt start :total 3 :comments [(nth comments start)]})
                               (stub config method path payload)))]
      (let [proposal (agent/preview-proposal (runtime config) handoff)]
        (is (= ["30001" "30002" "30003"] (mapv :id (:comments (jira/read-item-transition config proposal)))))
        (with-redefs [jira/api! (fn [& _] {:startAt 0 :total 3 :comments []})]
          (is (= :invalid-comment-metadata (:code (error-data #(jira/read-transition-comments config "10001"))))))))
    (is (empty? (writes calls)))))

(deftest real-json-cli-and-native-http-payload-complete-the-approved-handoff
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)
        prefix (str (jira/api-base-url config) jira/api-path)
        unexpected (fn [& _] (throw (Exception. "No unrelated native mutation")))
        input (json/generate-string (assoc handoff :fields {:customfield_123 [{:id "blue"} {:id "green"}]}))]
    (with-redefs [agent/tracker-runtime (fn [_] (runtime config))
                  agent/forge-runtime unexpected
                  http/get (fn [url options]
                             {:status 200 :body (json/generate-string (stub config :get (subs url (count prefix)) (:query-params options)))})
                  http/post (fn [url options]
                              (let [path (subs url (count prefix))
                                    result (stub config :post path (json/parse-string (:body options) true))]
                                {:status (if (str/ends-with? path "/comment") 201 204)
                                 :body (if result (json/generate-string result) "")}))
                  http/put unexpected http/delete unexpected]
      (let [preview (agent/run ["preview" "--request" input])
            _ (is (= 0 (:exit preview)) (pr-str preview))
            id (get-in preview [:envelope :data :proposalId])
            applied (agent/run ["apply" "--request" input "--approve" id])]
        (is (= 0 (:exit applied)) (pr-str applied))
        (is (= ["succeeded" "succeeded"] (mapv :status (get-in applied [:envelope :data :operations]))))
        (is (= "4" (get-in applied [:envelope :data :item :nativeStatus :id])))
        (is (= [{:id "blue"} {:id "green"}] (get-in (last (writes calls)) [:payload :fields :customfield_123])))
        (is (= (jira/text->adf (:comment handoff)) (get-in (first (writes calls)) [:payload :body])))
        (is (= ["/issue/10001/comment" "/issue/10001/transitions"] (mapv :path (writes calls))))))))

(deftest changed-native-status-between-preflight-and-write-is-a-known-nonmutation
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available) reads (atom 0)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (when (and (= :get method) (= path "/issue/APP-1") (= 3 (swap! reads inc)))
                               (swap! state assoc-in [:fields :status] review))
                             (stub config method path payload))]
      (let [preview (agent/preview-data (runtime config) handoff)
            partial (:partial-result (error-data #(agent/apply-data! (runtime config) handoff (:proposalId preview))))]
        (is (= [:failed :pending] (mapv :status (:outcomes partial))))
        (is (= "stale-transition-target" (get-in partial [:outcomes 0 :error :code])))
        (is (empty? (writes calls)))))))

(deftest confirmed-write-with-wrong-final-status-does-not-claim-exact-transition-completion
  (let [state (atom issue) calls (atom []) available (atom transitions)
        stub (api-stub state calls available)]
    (with-redefs [jira/api! (fn [config method path payload]
                             (let [result (stub config method path payload)]
                               (when (= :post method)
                                 (swap! state assoc-in [:fields :status] progress))
                               result))]
      (let [preview (agent/preview-data (runtime config) request)
            partial (:partial-result (error-data #(agent/apply-data! (runtime config) request (:proposalId preview))))]
        (is (= :succeeded (get-in partial [:outcomes 0 :status])))
        (is (= "3" (get-in partial [:readback :item :native-status :id])))
        (is (:readback-error partial))
        (is (= :item-transition-recovery-unavailable
               (:code (error-data #(agent/apply-data! (runtime config) request (:proposalId preview))))))
        (is (= 1 (count (writes calls))))))))
