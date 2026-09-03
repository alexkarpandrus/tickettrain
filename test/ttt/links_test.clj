(ns ttt.links-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.links :as links]))

(def pull-request
  {:ref (domain/contained-identity :github :change-request "org/repo" 7)
   :display-id "org/repo#7"
   :title "Improve retry handling"
   :url "https://github.com/org/repo/pull/7"})

(deftest identity-key-preserves-the-existing-github-source-marker
  (is (= "github:org/repo#7"
         (domain/identity-key (:ref pull-request))))
  (is (domain/same-identity?
       (:ref pull-request)
       (domain/key-identity "github:org/repo#7"))))

(deftest non-legacy-contained-identity-keys-round-trip
  (let [ref (domain/contained-identity :gitlab :change-request "group/repo" 8)]
    (is (domain/same-identity?
         ref
         (domain/key-identity (domain/identity-key ref))))))

(deftest upsert-change-request-preserves-user-authored-description
  (let [updated (links/upsert-change-request "User-authored context" pull-request)]
    (is (str/starts-with? updated "User-authored context"))
    (is (str/includes? updated links/begin-marker))
    (is (str/includes? updated "org/repo#7 — Improve retry handling"))))

(deftest upsert-change-request-replaces-the-same-source-without-duplication
  (let [initial (links/upsert-change-request "Context" pull-request)
        updated (links/upsert-change-request initial
                                             (assoc pull-request
                                                    :title "Improve retries safely"))]
    (is (= 1 (count (re-seq #"ttt:source" updated))))
    (is (str/includes? updated "Improve retries safely"))
    (is (not (str/includes? updated "Improve retry handling")))))

(deftest upsert-change-request-escapes-markdown-labels-and-destinations
  (let [updated (links/upsert-change-request
                 "Context"
                 (assoc pull-request
                        :title "Handle [retry](now) \\ safely"
                        :url "https://example.com/pull request/(7)"))]
    (is (str/includes? updated "Handle \\[retry\\]\\(now\\) \\\\ safely"))
    (is (str/includes? updated "https://example.com/pull%20request/%287%29"))))

(deftest upsert-change-request-preserves-links-from-other-providers
  (let [first-link (links/upsert-change-request "Context" pull-request)
        second-link (links/upsert-change-request
                     first-link
                     {:ref (domain/contained-identity :gitlab :change-request "group/repo" 8)
                      :display-id "group/repo!8"
                      :title "Add timeout metrics"
                      :url "https://gitlab.example/group/repo/-/merge_requests/8"})]
    (is (= 2 (count (links/managed-entries second-link))))
    (is (str/includes? second-link "org/repo#7"))
    (is (str/includes? second-link "group/repo!8"))))

(deftest upsert-change-request-rejects-reversed-managed-markers
  (is (thrown-with-msg?
       Exception
       #"malformed or duplicate"
       (links/upsert-change-request
        (str "Context\n" links/end-marker "\n" links/begin-marker)
        pull-request))))

(deftest upsert-change-request-rejects-an-orphan-source-marker
  (let [description (str links/begin-marker "\n"
                         "## " links/section-title "\n\n"
                         (links/source-marker pull-request) "\n"
                         "Unrelated text\n"
                         links/end-marker)]
    (is (thrown-with-msg?
         Exception
         #"malformed or duplicate"
         (links/upsert-change-request description pull-request)))))

(deftest upsert-change-request-rejects-an-orphan-link
  (let [description (str links/begin-marker "\n"
                         "## " links/section-title "\n\n"
                         (links/source-link pull-request) "\n"
                         links/end-marker)]
    (is (thrown-with-msg?
         Exception
         #"malformed or duplicate"
         (links/upsert-change-request description pull-request)))))

(deftest upsert-change-request-rejects-interleaved-entries
  (let [other (assoc pull-request
                     :ref (domain/contained-identity :gitlab
                                                     :change-request
                                                     "group/repo"
                                                     8)
                     :display-id "group/repo!8")
        description (str links/begin-marker "\n"
                         "## " links/section-title "\n\n"
                         (links/source-marker pull-request) "\n"
                         (links/source-marker other) "\n"
                         (links/source-link pull-request) "\n"
                         (links/source-link other) "\n"
                         links/end-marker)]
    (is (thrown-with-msg?
         Exception
         #"malformed or duplicate"
         (links/upsert-change-request description pull-request)))))

(deftest upsert-change-request-rejects-multiple-links-in-one-entry-line
  (let [description (str links/begin-marker "\n"
                         "## " links/section-title "\n\n"
                         (links/source-marker pull-request) "\n"
                         "- [one](https://example.com/1) - [two](https://example.com/2)\n"
                         links/end-marker)]
    (is (thrown-with-msg?
         Exception
         #"malformed or duplicate"
         (links/upsert-change-request description pull-request)))))
