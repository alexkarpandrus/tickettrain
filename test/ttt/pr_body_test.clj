(ns ttt.pr-body-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.text.change-request :as change-request]
            [ttt.domain :as domain]))

(def config
  {:change-request
   {:body-begin-marker "<!-- ttt:begin -->"
    :body-end-marker "<!-- ttt:end -->"
    :section-title "Tracker"}})

(def issue
  {:ref (domain/identity :linear :tracker-item "issue-99")
   :display-id "PAY-99"
   :url "https://linear.app/example/issue/PAY-99"})

(def parent
  {:ref (domain/identity :linear :tracker-item "issue-1")
   :display-id "PAY-1"
   :url "https://linear.app/example/issue/PAY-1"})

(deftest appends-managed-section-when-missing
  (let [updated (change-request/upsert-managed-section "Existing body" config issue parent)]
    (is (str/includes? updated "PAY-99"))
    (is (str/includes? updated "PAY-1"))
    (is (domain/same-identity?
         (:ref issue)
         (change-request/managed-item-ref updated config)))))

(deftest legacy-item-shapes-remain-readable-during-the-sliced-migration
  (let [updated (change-request/upsert-managed-section
                 "Existing body"
                 config
                 {:identifier "PAY-99"
                  :url "https://linear.app/example/issue/PAY-99"}
                 {:identifier "PAY-1"
                  :url "https://linear.app/example/issue/PAY-1"})]
    (is (= "PAY-99"
           (change-request/managed-issue-identifier updated config)))))

(deftest replaces-managed-section-when-present
  (let [existing (change-request/upsert-managed-section "Hello" config issue parent)
        updated (change-request/upsert-managed-section existing
                                                       config
                                                       (assoc issue :display-id "PAY-100")
                                                       parent)]
    (is (str/includes? updated "- Issue: [PAY-100]("))
    (is (not (str/includes? updated "- Issue: [PAY-99](")))))


(deftest replacing-managed-section-preserves-suffix-indentation
  (let [managed (change-request/upsert-managed-section "Hello" config issue parent)
        existing (str managed "\n    printf 'keep code'")
        updated (change-request/upsert-managed-section existing config issue parent)]
    (is (str/includes? updated "\n\n    printf 'keep code'"))))

(deftest managed-section-can-reference-projects
  (let [project {:ref (domain/identity :linear :project "project-1")
                 :label "Project"
                 :display-id "MDP Containers"
                 :url "https://linear.app/example/project/mdp-containers"}
        updated (change-request/upsert-managed-section "Existing body" config issue project)]
    (is (str/includes? updated "Project"))
    (is (str/includes? updated "MDP Containers"))))

(deftest managed-section-escapes-cross-system-markdown
  (let [special-item (assoc issue
                            :display-id "PAY-[99](retry)"
                            :url "https://linear.app/example issue/(PAY-99)")
        special-parent (assoc parent
                              :display-id "PAY-[1]"
                              :label "Parent [scope]")
        updated (change-request/upsert-managed-section
                 "Body"
                 config
                 special-item
                 special-parent)]
    (is (str/includes? updated "PAY-\\[99\\]\\(retry\\)"))
    (is (str/includes? updated "example%20issue/%28PAY-99%29"))
    (is (str/includes? updated "Parent \\[scope\\]"))))

(deftest managed-section-can-link-an-existing-item-without-context
  (let [updated (change-request/upsert-managed-section "Existing body" config issue nil)]
    (is (str/includes? updated "PAY-99"))
    (is (not (str/includes? updated "Parent:")))))

(deftest strips-managed-section
  (let [existing (change-request/upsert-managed-section "Hello" config issue parent)]
    (is (= "Hello" (change-request/strip-managed-section existing config)))))

(deftest strips-legacy-ordered-managed-content
  (is (= "Actual body"
         (change-request/strip-managed-section
          "Actual body\n\n<!-- ttt:begin -->\nmanaged\n<!-- ttt:end -->\n"
          config))))

(deftest ignores-item-like-text-outside-the-managed-section
  (is (nil? (change-request/managed-item-ref
             "User text\n- Issue: [PAY-88](https://example.com)"
             config))))

(deftest rejects-a-non-item-managed-marker
  (let [body (str "<!-- ttt:begin -->\n"
                  "## Tracker\n\n"
                  "<!-- ttt:item linear:project:project-1 -->\n"
                  "- Issue: [PAY-99](https://linear.app/example/issue/PAY-99)\n"
                  "<!-- ttt:end -->")]
    (is (thrown-with-msg?
         Exception
         #"malformed or duplicate"
         (change-request/upsert-managed-section body config issue parent)))))

(deftest rejects-unbalanced-managed-markers
  (is (thrown-with-msg?
       Exception
       #"malformed or duplicate"
       (change-request/upsert-managed-section
        "Hello\n<!-- ttt:begin -->\n"
        config
        issue
        parent))))

(deftest rejects-reversed-managed-markers
  (is (thrown-with-msg?
       Exception
       #"malformed or duplicate"
       (change-request/upsert-managed-section
        "<!-- ttt:end -->\n<!-- ttt:begin -->"
        config
        issue
        parent))))

(deftest rejects-duplicate-managed-sections
  (let [section (change-request/managed-section config issue parent)]
    (is (thrown-with-msg?
         Exception
         #"malformed or duplicate"
         (change-request/strip-managed-section
          (str section "\n" section)
          config)))))

(deftest managed-section-project-context-renders-name-and-url
  (let [project {:ref (domain/identity :linear :project "project-1")
                 :label "Project"
                 :kind :project
                 :display-id "mdp-containers"
                 :title "MDP Containers"
                 :url "https://linear.app/example/project/mdp-containers"}
        updated (change-request/upsert-managed-section "Existing body" config issue project)]
    (is (str/includes? updated "- Project: [MDP Containers](https://linear.app/example/project/mdp-containers)"))
    (is (not (str/includes? updated "[mdp-containers](")))
    (is (not (str/includes? updated "]()")))))
