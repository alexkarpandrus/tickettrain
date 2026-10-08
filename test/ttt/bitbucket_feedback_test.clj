(ns ttt.bitbucket-feedback-test
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.feedback-test-support :as support]
            [ttt.platform.feedback-journal :as journal]
            [ttt.providers.forge.bitbucket :as bitbucket]))

(use-fixtures :each support/with-journal)

(def workspace-id "{00000000-0000-0000-0000-000000000001}")
(def repo-id "{00000000-0000-0000-0000-000000000002}")
(def old-user {:uuid "{00000000-0000-0000-0000-000000000003}" :nickname "existing" :display_name "Existing"})
(def new-user {:uuid "{00000000-0000-0000-0000-000000000004}" :nickname "requested" :display_name "Requested"})
(def cfg {:forge {:provider :bitbucket :email "test@example.invalid" :api-token "test-only"}})
(def pr-path (str "/repositories/" workspace-id "/" repo-id "/pullrequests/7"))
(def comments-path (str pr-path "/comments"))

(defn native-note [id parent]
  (cond-> {:id id :pullrequest {:id 7} :content {:raw "Original"} :user old-user :deleted false
           :created_on "2026-10-01" :updated_on "2026-10-01" :inline {:path "file.clj" :to 7}}
    parent (assoc :parent {:id parent})))

(defn state []
  {:repo (atom {:uuid repo-id :full_name "ws/repo" :mainbranch {:name "main"}})
   :workspace (atom {:uuid workspace-id :slug "ws"})
   :pr (atom {:id 7 :title "Keep title" :description "Keep description" :state "OPEN"
              :destination {:repository {:uuid repo-id} :branch {:name "main"}}
              :source {:branch {:name "feature"} :commit {:hash "head-a"}} :reviewers [old-user]})
   :comments (atom [(native-note 1 nil) (native-note 10 nil) (native-note 11 10) (native-note 12 11)])
   :writes (atom []) :reads (atom []) :failure (atom nil) :next-page (atom nil) :outage (atom false)})

(defn response [status body]
  {:status status :body (if (= status 204) "" (json/generate-string body))})

(defn native-http [state method]
  (fn [url options]
    (let [uri (java.net.URI. url) path (.getPath uri)
          query (:query-params options)
          fields (when (:body options) (json/parse-string (:body options) true))]
      (is (= "https" (.getScheme uri)))
      (is (= "api.bitbucket.org" (.getHost uri)))
      (is (false? (:throw options)))
      (if (= :get method)
        (do
          (swap! (:reads state) conj [path query])
          (when @(:outage state) (throw (ex-info "Outage" {})))
          (response 200
                    (cond
                      (= path "/2.0/repositories/ws/repo") @(:repo state)
                      (= path "/2.0/workspaces/ws") @(:workspace state)
                      (= path "/2.0/user") old-user
                      (= path (str "/2.0/users/" (:uuid old-user))) old-user
                      (= path (str "/2.0/users/" (:uuid new-user))) new-user
                      (= path (str "/2.0" pr-path)) @(:pr state)
                      (= path (str "/2.0" comments-path))
                      (let [page (parse-long (get query "page" "1"))
                            notes (vec (take 100 (drop (* (dec page) 100) @(:comments state))))
                            more? (< (* page 100) (count @(:comments state)))]
                        (cond-> {:values notes}
                          more? (assoc :next (str url "?page=" (inc page) "&pagelen=100"))
                          @(:next-page state) (assoc :next @(:next-page state))))
                      :else (throw (ex-info "Unexpected read" {:path path})))))
        (let [path (subs path (count "/2.0"))
              failure @(:failure state)
              failing? (and (= method (:method failure)) (= path (:path failure)))
              _ (swap! (:writes state) conj [method path fields])]
          (if (and failing? (not (:accepted? failure)))
            (do (reset! (:failure state) nil) (response (:status failure) {:error {:message "Rejected"}}))
            (let [id (some-> (re-find #"/comments/(\d+)" path) second parse-long)
                  index (first (keep-indexed #(when (= id (:id %2)) %1) @(:comments state)))
                  result
                  (cond
                    (and (= :post method) (= comments-path path))
                    (let [note (assoc (native-note 1001 (get-in fields [:parent :id])) :content (:content fields))]
                      (is (= #{:content :parent} (set (keys fields))))
                      (swap! (:comments state) conj note)
                      (response 201 note))
                    (and (= :put method) id)
                    (do (is (= #{:content} (set (keys fields))))
                        (swap! (:comments state) update index assoc :content (:content fields) :updated_on "2026-10-02")
                        (response 200 (get @(:comments state) index)))
                    (str/ends-with? path "/resolve")
                    (if (= method :post)
                      (do (swap! (:comments state) assoc-in [index :resolution] {:user old-user :created_on "2026-10-02"})
                          (response 200 {:user old-user :created_on "2026-10-02"}))
                      (do (is (= :delete method))
                          (swap! (:comments state) update index dissoc :resolution)
                          (response 204 nil)))
                    (and (= :put method) (= path pr-path))
                    (do (is (= #{:reviewers} (set (keys fields))))
                        (swap! (:pr state) assoc :reviewers
                               (mapv {(:uuid old-user) old-user (:uuid new-user) new-user}
                                     (map :uuid (:reviewers fields))))
                        (response 200 @(:pr state)))
                    :else (throw (ex-info "Unexpected mutation" {:method method :path path})))]
              (if failing?
                (do (reset! (:failure state) nil) (response (:status failure) {:error {:message "Lost response"}}))
                result))))))))

(defn with-bitbucket [state run]
  (with-redefs [config/load-config (fn [& _] cfg)
                bitbucket/remote-slug (constantly "ws/repo")
                http/get (native-http state :get)
                http/post (native-http state :post)
                http/put (native-http state :put)
                http/delete (native-http state :delete)]
    (run)))

(def reply {:type "reply" :discussion "10" :body "Fixed"})
(def edit {:type "edit_note" :discussion "10" :note "12" :body "Corrected"})
(def resolve-thread {:type "resolve_discussion" :discussion "10" :resolved true})
(def reviewers {:type "update_reviewers" :reviewers [(:uuid new-user)]})

(deftest native-http-workflow-targets-the-second-tree-and-preserves-unrelated-metadata
  (let [s (state) before @(:pr s) first-note (first @(:comments s))
        request (support/request [reply edit resolve-thread reviewers])]
    (with-bitbucket s
      #(let [preview (support/preview request)
             data (get-in preview [:envelope :data])
             id (:proposalId data)]
         (is (= 0 (:exit preview)) (pr-str preview))
         (is (= (str "https://api.bitbucket.org/" workspace-id "/" repo-id)
                (get-in data [:changeRequest :nativeIdentity :container])))
         (is (= ["10" "11" "12"] (mapv (fn [n] (get-in n [:identity :id]))
                                      (get-in (agent/run ["inspect" "--feedback" "--change-request" "7"])
                                              [:envelope :data :feedback :discussions 1 :notes]))))
         (is (empty? @(:writes s)))
         (is (empty? (seq (.listFiles (journal/directory)))))
         (is (= 2 (:exit (support/apply-request (assoc-in request [:operations 0 :body] "Unapproved") id))))
         (is (empty? @(:writes s)))
         (let [result (support/apply-request request id)]
           (is (= 0 (:exit result)) (pr-str result))
           (is (= first-note (first @(:comments s))))
           (is (= "Corrected" (get-in @(:comments s) [3 :content :raw])))
           (is (= {:id 10} (:parent (last @(:comments s)))))
           (is (= "Fixed" (get-in (last @(:comments s)) [:content :raw])))
           (is (some? (:resolution (get @(:comments s) 1))))
           (is (= (dissoc before :reviewers) (dissoc @(:pr s) :reviewers)))
           (is (= [old-user new-user] (:reviewers @(:pr s))))
           (is (= ["1"] (mapv :id (get-in result [:envelope :data :feedback :unresolvedDiscussions]))))
           (let [writes @(:writes s)]
             (is (= id (support/proposal-id request)))
             (is (= 0 (:exit (support/apply-request request id))))
             (is (= writes @(:writes s)))))))))

(deftest reopen-replace-and-clear-use-native-delete-and-exact-reviewer-only-payloads
  (let [s (state)]
    (swap! (:comments s) assoc-in [1 :resolution] {:user old-user})
    (with-bitbucket s
      #(let [request (support/request [(assoc resolve-thread :resolved false) (assoc reviewers :replace true)])]
         (is (= 0 (:exit (support/apply-request request (support/proposal-id request)))))
         (is (nil? (:resolution (get @(:comments s) 1))))
         (is (= [new-user] (:reviewers @(:pr s))))
         (is (= [:delete (str comments-path "/10/resolve") nil] (first @(:writes s))))
         (let [clear (assoc (support/request [{:type "update_reviewers" :reviewers [] :replace true}]) :batchId "clear")]
           (is (= 0 (:exit (support/apply-request clear (support/proposal-id clear)))))
           (is (= [] (:reviewers @(:pr s)))))))))

(deftest comments-pages-reconstruct-trees-across-page-boundaries
  (let [s (state)]
    (reset! (:comments s) (vec (concat (map #(native-note % nil) (range 1 101)) [(native-note 101 10)])))
    (with-bitbucket s
      #(let [result (agent/run ["inspect" "--feedback" "--change-request" "7"])
             feedback (get-in result [:envelope :data :feedback])]
         (is (= 0 (:exit result)) (pr-str result))
         (is (= 100 (count (:discussions feedback))))
         (is (= ["10" "101"] (mapv (fn [n] (get-in n [:identity :id])) (get-in feedback [:discussions 9 :notes]))))
         (is (= 100 (count (:unresolvedDiscussions feedback))))))))

(deftest unsafe-pagination-links-fail-without-contacting-another-origin-or-target
  (doseq [next ["https://evil.example/2.0/comments?page=2"
               "https://api.bitbucket.org/2.0/repositories/other/repo/pullrequests/7/comments?page=2"
               (str "https://api.bitbucket.org/2.0" (str/replace (str/replace comments-path "{" "%7B") "}" "%7D") "?page=2&q=deleted%3Dfalse")
               (str "https://api.bitbucket.org/2.0" (str/replace (str/replace comments-path "{" "%7B") "}" "%7D") "?page=2#fragment")]]
    (let [s (state)]
      (reset! (:next-page s) next)
      (with-bitbucket s
        #(let [result (support/preview (support/request [reply]))]
           (is (= "provider-response-invalid" (get-in result [:envelope :error :code])))))
      (is (empty? @(:writes s))))))

(deftest invalid-comment-trees-user-ownership-and-native-identities-fail-before-mutations
  (doseq [change [(fn [s] (swap! (:workspace s) assoc :slug "other"))
                  (fn [s] (swap! (:repo s) assoc :full_name "other/repo"))
                  (fn [s] (swap! (:pr s) assoc :id 8))
                  (fn [s] (swap! (:pr s) assoc-in [:destination :repository :uuid] workspace-id))
                  (fn [s] (swap! (:comments s) assoc-in [3 :pullrequest :id] 8))
                  (fn [s] (swap! (:comments s) assoc-in [3 :parent :id] 99))
                  (fn [s] (swap! (:comments s) assoc-in [1 :parent] {:id 12}))
                  (fn [s] (swap! (:comments s) assoc-in [3 :user] new-user))
                  (fn [s] (swap! (:comments s) assoc-in [3 :deleted] true))
                  (fn [s] (swap! (:comments s) assoc-in [1 :deleted] true))]]
    (let [s (state)]
      (change s)
      (with-bitbucket s #(is (= 2 (:exit (support/preview (support/request [reply edit resolve-thread]))))))
      (is (empty? @(:writes s)))))
  (let [s (state)]
    (with-bitbucket s
      #(doseq [operations [[(assoc edit :note "1")] [(assoc reviewers :reviewers ["not-a-uuid"])]]]
         (is (= 2 (:exit (support/preview (support/request operations)))))))
    (is (empty? @(:writes s)))))

(deftest stale-source-head-repository-uuid-and-note-state-invalidate-approval
  (doseq [change [(fn [s] (swap! (:pr s) assoc-in [:source :commit :hash] "head-b"))
                  (fn [s] (swap! (:repo s) assoc :uuid workspace-id))
                  (fn [s] (swap! (:comments s) assoc-in [3 :content :raw] "Concurrent edit"))
                  (fn [s] (swap! (:comments s) assoc-in [3 :user] new-user))]]
    (let [s (state) request (support/request [edit])]
      (with-bitbucket s
        #(let [id (support/proposal-id request)]
           (change s)
           (is (= 2 (:exit (support/apply-request request id))))))
      (is (empty? @(:writes s))))))

(deftest rejected-native-write-retries-without-duplicating-an-acknowledged-reply
  (let [s (state) request (support/request [reply edit])]
    (reset! (:failure s) {:method :put :path (str comments-path "/12") :status 403})
    (with-bitbucket s
      #(let [id (support/proposal-id request)
             result (support/apply-request request id)]
         (is (= ["succeeded" "failed"] (mapv :status (get-in result [:envelope :error :partialResult :operations]))))
         (is (= id (support/proposal-id request)))
         (is (= 0 (:exit (support/apply-request request id))))
         (is (= 1 (count (filter (fn [[method path]] (and (= :post method) (= comments-path path))) @(:writes s)))))))))

(deftest ambiguous-accepted-replies-never-repeat-and-outages-retain-successes
  (doseq [status [408 500]]
    (let [s (state) request (assoc (support/request [reply edit]) :batchId (str "status-" status))]
      (reset! (:failure s) {:method :post :path comments-path :status status :accepted? true})
      (with-bitbucket s
        #(let [id (support/proposal-id request) result (support/apply-request request id)]
           (is (= ["unknown" "pending"] (mapv :status (get-in result [:envelope :error :partialResult :operations]))))
           (is (= 2 (:exit (support/apply-request request id))))
           (is (= 1 (count @(:writes s))))))))
  (let [s (state) request (assoc (support/request [reply edit]) :batchId "outage")]
    (reset! (:failure s) {:method :put :path (str comments-path "/12") :status 403})
    (with-bitbucket s
      #(let [id (support/proposal-id request)]
         (support/apply-request request id)
         (reset! (:outage s) true)
         (is (= "1001" (get-in (support/apply-request request id)
                              [:envelope :error :partialResult :operations 0 :affectedIds :noteId])))
         (is (= 2 (count @(:writes s))))))))

(deftest closed-pr-reviewer-updates-fail-preview-and-pending-apply
  (let [s (state)]
    (swap! (:pr s) assoc :state "MERGED")
    (with-bitbucket s
      #(is (= "change-request-not-editable"
              (get-in (support/preview (support/request [reviewers])) [:envelope :error :code]))))
    (is (empty? @(:writes s))))
  (let [s (state) request (support/request [reply reviewers])]
    (reset! (:failure s) {:method :put :path pr-path :status 403})
    (with-bitbucket s
      #(let [id (support/proposal-id request)]
         (support/apply-request request id)
         (swap! (:pr s) assoc :state "MERGED")
         (let [before @(:writes s) retry (support/apply-request request id)]
           (is (= 2 (:exit retry)))
           (is (= before @(:writes s))))))))

(deftest malformed-and-cyclic-pages-are-rejected
  (with-redefs [bitbucket/api! (fn [& _] {:values nil})]
    (is (thrown? Exception (bitbucket/feedback-pages cfg comments-path))))
  (let [s (state)]
    (reset! (:next-page s) (str "https://api.bitbucket.org/2.0"
                              (str/replace (str/replace comments-path "{" "%7B") "}" "%7D") "?page=2"))
    (with-bitbucket s
      #(is (= "provider-response-invalid"
              (get-in (support/preview (support/request [reply])) [:envelope :error :code]))))
    (is (empty? @(:writes s)))))
