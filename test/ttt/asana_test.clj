(ns ttt.asana-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.providers.tracker.asana :as asana]))

(def config {:tracker {:provider :asana :token "tok" :workspace "w"}})

(def scope (domain/scope-identity :asana "w"))

(deftest task-fields-include-related-resource-names
  (doseq [field ["html_notes" "projects.name" "tags.name" "parent.name"]]
    (is (str/includes? asana/task-fields field))))

(deftest rich-notes-round-trip-generated-markdown
  (let [markdown (str "_Empty._\n\n"
                      "## Pull requests\n\n"
                      "- [repo#1 — \\[123\\] title](https://bitbucket.org/repo/pull-requests/1)")
        html (asana/markdown->html markdown)]
    (is (str/includes? html "<h2>Pull requests</h2>"))
    (is (str/includes? html "<a href=\"https://bitbucket.org/repo/pull-requests/1\">repo#1 — [123] title</a>"))
    (is (= markdown (asana/html->markdown html)))))

(deftest normalizes-task-with-project-parent-and-tags
  (let [task {:gid "123" :name "Retry" :notes "Body" :permalink_url "https://app.asana.com/0/0/123"
              :completed false
              :projects [{:gid "p1" :name "App"}]
              :parent {:gid "99" :name "Parent"}
              :tags [{:gid "t1" :name "bug"}]}
        item (asana/normalize-task scope task)]
    (is (= "123" (:display-id item)))
    (is (= "Retry" (:title item)))
    (is (= "Body" (:description item)))
    (is (= "incomplete" (get-in item [:state :name])))
    (is (= "App" (get-in item [:project :display-id])))
    (is (= "99" (get-in item [:parent :display-id])))
    (is (= ["bug"] (mapv :display-id (:labels item))))
    (is (domain/entity-in-scope? item scope))))

(deftest normalizes-rich-task-notes
  (let [item (asana/normalize-task
              scope
              {:gid "123" :name "Retry" :html_notes "<body><h2>Links</h2><ul><li><a href=\"https://example.com\">Example</a></li></ul></body>"})]
    (is (= "## Links\n\n- [Example](https://example.com)" (:description item)))))

(deftest keeps-canonical-notes-for-legacy-plain-rich-text
  (let [notes "## Pull requests\n\n- [PR](https://example.com/pr/1)"
        item (asana/normalize-task
              scope
              {:gid "123" :name "Retry" :notes notes
               :html_notes "<body>## Pull requests\n\n- [PR](<a href=\"https://example.com/pr/1\">https://example.com/pr/1</a>)</body>"})]
    (is (= notes (:description item)))))

(deftest rejects-rich-text-entity-declarations
  (is (= :provider-data-invalid
         (try
           (asana/html->markdown "<!DOCTYPE body [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><body>&x;</body>")
           nil
           (catch clojure.lang.ExceptionInfo ex
             (:code (ex-data ex)))))))

(deftest tag-ids-extract-native-gids
  (is (= ["t1" "t2"]
         (asana/tag-ids [(asana/normalize-tag scope {:gid "t1" :name "a"})
                         (asana/normalize-tag scope {:gid "t2" :name "b"})]))))

(deftest free-plan-search-falls-back-to-assigned-tasks
  (let [calls (atom [])]
    (with-redefs [asana/api! (fn [_ method path query]
                               (swap! calls conj [method path query])
                               (case path
                                 "/workspaces/w/tasks/search"
                                 (throw (ex-info "premium" {:status 402}))
                                 "/tasks"
                                 {:data [{:gid "1" :name "Retry sync" :notes "Body"}
                                         {:gid "2" :name "Other" :notes "Body"}]}))]
      (is (= ["1"] (mapv :display-id (asana/search-tasks config "retry")))))
    (is (= "me" (get-in (second @calls) [2 :assignee])))))

(deftest neutral-adapter-declares-every-tracker-capability
  (let [adapter (asana/neutral-adapter config)]
    (is (= :asana (:provider adapter)))
    (is (= asana/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) asana/capabilities))))
