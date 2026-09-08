(ns ttt.asana-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.cli.prompt :as prompt]
            [ttt.domain :as domain]
            [ttt.providers.tracker.asana :as asana]
            [ttt.text.links :as links]))

(def config {:tracker {:provider :asana :token "tok" :workspace "w"}})

(def scope (domain/scope-identity :asana "w"))

(deftest task-fields-include-related-resource-names
  (doseq [field ["html_notes" "workspace.gid" "projects.name" "tags.name" "parent.name"]]
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


(deftest task-scope-comes-from-the-api-response
  (with-redefs [asana/api! (fn [& _]
                             {:data {:gid "123"
                                     :name "Other workspace"
                                     :workspace {:gid "other"}}})]
    (let [item (asana/task-by-gid config "123")]
      (is (domain/entity-in-scope? item (domain/scope-identity :asana "other")))
      (is (not (domain/entity-in-scope? item scope))))))

(deftest normalizes-rich-task-notes
  (let [item (asana/normalize-task
              scope
              {:gid "123" :name "Retry" :html_notes "<body><h2>Links</h2><ul><li><a href=\"https://example.com\">Example</a></li></ul></body>"})]
    (is (= "## Links\n\n- [Example](https://example.com)" (:description item)))))


(deftest update-preserves-rich-html-outside-the-managed-section
  (let [original (str "<body><strong data-custom=\"keep\">Human</strong>\n"
                      "<h2><u>Pull requests</u></h2><ul><li><a href=\"https://example.com/old\">old</a></li></ul>"
                      "<h2>Notes</h2><mention data-asana-gid=\"user-1\">Alex</mention></body>")
        item (asana/normalize-task scope {:gid "123" :name "Retry" :html_notes original})
        description (links/upsert-change-request
                     (:description item)
                     {:display-id "acme/repo#1"
                      :title "Retry"
                      :url "https://github.com/acme/repo/pull/1"})
        sent (atom nil)]
    (with-redefs [asana/api! (fn [_ _ _ body] (reset! sent body))]
      (asana/update-item-from-intent! config item {:description description :labels []}))
    (let [html (get-in @sent [:data :html_notes])]
      (is (str/includes? html "<strong data-custom=\"keep\">Human</strong>"))
      (is (str/includes? html "<mention data-asana-gid=\"user-1\">Alex</mention>"))
      (is (str/includes? html "https://github.com/acme/repo/pull/1"))
      (is (not (str/includes? html "<u>Pull requests</u>"))))))

(deftest update-preserves-a-user-authored-lowercase-heading
  (let [original "<body><h2>pull requests</h2><strong>Keep these instructions</strong></body>"
        item (asana/normalize-task scope {:gid "123" :name "Retry" :html_notes original})
        description (links/upsert-change-request
                     (:description item)
                     {:display-id "acme/repo#1"
                      :title "Retry"
                      :url "https://github.com/acme/repo/pull/1"})
        html (asana/update-description-html item description)]
    (is (str/includes? html "<h2>pull requests</h2>"))
    (is (str/includes? html "<strong>Keep these instructions</strong>"))
    (is (str/includes? html "<h2>Pull requests</h2>"))))

(deftest keeps-canonical-notes-for-legacy-plain-rich-text
  (let [notes "## Pull requests\n\n- [PR](https://example.com/pr/1)"
        item (asana/normalize-task
              scope
              {:gid "123" :name "Retry" :notes notes
               :html_notes "<body>## Pull requests\n\n- [PR](<a href=\"https://example.com/pr/1\">https://example.com/pr/1</a>)</body>"})]
    (is (= notes (:description item)))
    (is (nil? (:provider-description item)))))

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

(deftest create-task-applies-the-configured-target-state
  (let [payload (atom nil)]
    (with-redefs [asana/api! (fn [_ _ _ body]
                               (reset! payload body)
                               {:data {:gid "123" :completed true}})]
      (asana/create-task!
       (assoc-in config [:tracker :target-state] "completed")
       {} "Title" "Body" []))
    (is (true? (get-in @payload [:data :completed])))
    (is (= "w" (get-in @payload [:data :workspace])))))

(deftest setup-selects-a-target-state
  (with-redefs [asana/api! (fn [& _] {:data {:name "Alex"}})
                prompt/choose-index (fn [& _] 2)]
    (is (= "completed"
           (get-in (asana/setup config) [:tracker :target-state])))))

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
