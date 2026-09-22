(ns ttt.github-issues-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.cli.prompt :as prompt]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]
            [ttt.providers.tracker.github-issues :as github-issues]))

(def scope (domain/scope-identity :github-issues "org/repo"))

(deftest parses-issue-numbers
  (is (= 123 (github-issues/parse-number "123")))
  (is (= 123 (github-issues/parse-number "#123")))
  (is (nil? (github-issues/parse-number "retry handling"))))

(deftest repo-slug-prefers-gh-repo-environment
  (with-redefs [github-issues/gh-json (fn [& _] (throw (Exception. "must not inspect remotes")))]
    (is (= "org/issues" (github-issues/repo-slug " org/issues ")))))

(deftest normalizes-issue-with-milestone-and-labels
  (let [issue {:number 123 :title "Retry" :body "Body" :url "https://github.com/org/repo/issues/123"
               :state "OPEN"
               :milestone {:number 1 :title "v1.0" :url "https://github.com/org/repo/milestone/1"}
               :labels [{:name "bug"} {:name "backend"}]}
        item (github-issues/normalize-item scope issue)]
    (is (= "#123" (:display-id item)))
    (is (= "Retry" (:title item)))
    (is (= "Body" (:description item)))
    (is (= "open" (:state item)))
    (is (= "v1.0" (get-in item [:project :display-id])))
    (is (= ["bug" "backend"] (mapv :display-id (:labels item))))
    (is (domain/entity-in-scope? item scope))))


(deftest closed-issue-reasons-map-to-neutral-terminal-states
  (is (re-find #"stateReason" github-issues/issue-fields))
  (is (= "completed"
         (:state (github-issues/normalize-item scope {:number 1
                                                      :state "CLOSED"
                                                      :stateReason "COMPLETED"}))))
  (is (= "canceled"
         (:state (github-issues/normalize-item scope {:number 2
                                                      :state "CLOSED"
                                                      :stateReason "NOT_PLANNED"})))))


(deftest list-items-widens-gh-pagination-until-the-filtered-limit-is-met
  (let [calls (atom [])
        completed (vec (repeat 100 {:state "completed"}))
        open {:display-id "#101" :state "open"}]
    (with-redefs [github-issues/list-issues (fn [_ limit]
                                             (swap! calls conj limit)
                                             (if (= 100 limit) completed (conj completed open)))]
      (is (= ["#101"]
             (mapv :display-id (github-issues/list-items scope #(= "open" (:state %)) 1))))
      (is (= [100 200] @calls)))))

(deftest create-rejects-parent-issues
  (is (thrown-with-msg? Exception #"does not support parent"
                        (github-issues/create-item-from-intent!
                         {}
                         scope
                         {:parent {:ref (domain/identity :github-issues :tracker-item 1)}}
                         {:title "T" :description "D" :labels []}))))

(deftest create-closes-an-issue-when-configured
  (let [calls (atom [])]
    (with-redefs [shell/run (fn [& args]
                              (swap! calls conj args)
                              (if (= ["gh" "issue" "create"] (take 3 args))
                                "https://github.com/org/repo/issues/7"
                                ""))
                  github-issues/issue-by-number (fn [_ number]
                                                  {:number number :state "completed"})]
      (is (= "completed"
             (:state (github-issues/create-item!
                      {:tracker {:target-state "closed"}}
                      scope {} "Title" "Body" [])))))
    (is (some #(= ["gh" "issue" "close" "7" "--repo" "org/repo"] %) @calls))))

(deftest update-reconciles-labels-in-the-configured-repository
  (let [calls (atom [])
        existing (github-issues/normalize-label scope "existing")
        removed (github-issues/normalize-label scope "removed")
        added (github-issues/normalize-label scope "added")]
    (with-redefs [shell/run (fn [& args] (swap! calls conj args))
                  github-issues/issue-by-number (fn [_ number] {:number number})]
      (github-issues/update-item! scope
                                  {:number 7 :labels [existing removed]}
                                  "Body"
                                  [existing added]))
    (is (= [["gh" "issue" "edit" "7" "--repo" "org/repo" "--body" "Body"
             "--add-label" "added" "--remove-label" "removed"]]
           @calls))))

(deftest setup-persists-the-repository-and-target-state
  (with-redefs [shell/run (fn [& _] "")
                prompt/choose-index (fn [& _] 1)]
    (is (= {:repository "org/repo" :target-state "open"}
           (:tracker (github-issues/setup {:tracker {:repository "org/repo"}}))))))

(deftest setup-authentication-failure-explains-recovery
  (with-redefs [shell/run (fn [& _] (throw (Exception. "not authenticated")))]
    (is (thrown-with-msg?
         Exception
         #"gh auth login"
         (github-issues/setup {})))))

(deftest comments-on-issues-use-the-configured-repository
  (let [request (atom nil)
        item {:number 7}]
    (with-redefs [shell/run (fn [& args] (reset! request args))]
      (github-issues/comment-item! scope item "Looks good"))
    (is (= ["gh" "issue" "comment" "7" "--repo" "org/repo" "--body" "Looks good"]
           @request))))

(deftest parent-resolution-is-rejected-before-preview
  (let [adapter (github-issues/neutral-adapter {:tracker {:repository "org/repo"}})]
    (try
      ((:resolve-parent-item adapter) "1")
      (is false "Expected unsupported-parent")
      (catch Exception ex
        (is (= :unsupported-parent (:code (ex-data ex))))))))

(deftest neutral-adapter-uses-the-configured-repository
  (with-redefs [github-issues/gh-json (fn [& _] (throw (Exception. "must not inspect the current directory")))]
    (let [adapter (github-issues/neutral-adapter {:tracker {:repository "org/repo"}})]
      (is (= :github-issues (:provider adapter)))
      (is (= scope ((:configured-scope adapter))))
      (is (= github-issues/capabilities (:capabilities adapter)))
      (is (empty? (:item-capabilities adapter)))
      (is (every? #(fn? (get adapter %)) github-issues/capabilities)))))
