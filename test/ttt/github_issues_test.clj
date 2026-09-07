(ns ttt.github-issues-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
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
    (is (= "OPEN" (get-in item [:state :name])))
    (is (= "v1.0" (get-in item [:project :display-id])))
    (is (= ["bug" "backend"] (mapv :display-id (:labels item))))
    (is (domain/entity-in-scope? item scope))))

(deftest create-rejects-parent-issues
  (is (thrown-with-msg? Exception #"does not support parent"
                        (github-issues/create-item-from-intent!
                         scope
                         {:parent {:ref (domain/identity :github-issues :tracker-item 1)}}
                         {:title "T" :description "D" :labels []}))))

(deftest parent-resolution-is-rejected-before-preview
  (let [adapter (with-redefs [github-issues/repo-slug (fn [] "org/repo")]
                  (github-issues/neutral-adapter {}))]
    (try
      ((:resolve-parent-item adapter) "1")
      (is false "Expected unsupported-parent")
      (catch Exception ex
        (is (= :unsupported-parent (:code (ex-data ex))))))))

(deftest neutral-adapter-declares-every-tracker-capability
  (with-redefs [github-issues/repo-slug (fn [] "org/repo")]
    (let [adapter (github-issues/neutral-adapter {})]
      (is (= :github-issues (:provider adapter)))
      (is (= github-issues/capabilities (:capabilities adapter)))
      (is (every? #(fn? (get adapter %)) github-issues/capabilities)))))
