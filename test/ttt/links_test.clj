(ns ttt.links-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.text.links :as links]))

(def pull-request
  {:ref (domain/contained-identity :github :change-request "org/repo" 7)
   :display-id "org/repo#7"
   :title "Improve retry handling"
   :url "https://github.com/org/repo/pull/7"})

(deftest identity-key-round-trips-through-domain
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
    (is (str/includes? updated "## Pull requests"))
    (is (str/includes? updated "org/repo#7 — Improve retry handling"))
    (is (not (str/includes? updated "<!--")))))

(deftest upsert-change-request-replaces-the-same-source-without-duplication
  (let [initial (links/upsert-change-request "Context" pull-request)
        updated (links/upsert-change-request initial
                                             (assoc pull-request
                                                    :title "Improve retries safely"))]
    (is (= 1 (count (re-seq #"org/repo#7" updated))))
    (is (str/includes? updated "Improve retries safely"))
    (is (not (str/includes? updated "Improve retry handling")))))


(deftest upsert-change-request-keeps-the-next-heading-separate
  (let [existing (str "Context\n\n"
                      "## Pull requests\n\n"
                      "- [old](https://example.com/old)\n\n"
                      "## Notes\n\nKeep this")
        updated (links/upsert-change-request existing pull-request)]
    (is (str/includes? updated "org/repo#7 — Improve retry handling"))
    (is (str/includes? updated "\n\n## Notes\n\nKeep this"))
    (is (= 2 (count (links/managed-entries updated))))))

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

(deftest upsert-change-request-migrates-legacy-markers
  (let [legacy (str "User context\n\n"
                    "<!-- ttt:pull-requests:begin -->\n"
                    "## Pull requests\n\n"
                    "<!-- ttt:source github:org/repo#7 -->\n"
                    "- [org/repo#7 — Old title](https://github.com/org/repo/pull/7)\n"
                    "<!-- ttt:pull-requests:end -->")
        updated (links/upsert-change-request legacy
                                             (assoc pull-request :title "New title"))]
    (is (not (str/includes? updated "ttt:pull-requests")))
    (is (not (str/includes? updated "ttt:source")))
    (is (str/includes? updated "New title"))
    (is (= 1 (count (re-seq #"org/repo#7" updated))))))

(deftest upsert-change-request-rejects-duplicate-headings
  (is (thrown-with-msg?
       Exception
       #"malformed or duplicate"
       (links/upsert-change-request
        "## Pull requests\n\n- [a](https://example.com/1)\n\n## Pull requests\n"
        pull-request))))

(deftest upsert-change-request-rejects-a-malformed-link-line
  (is (thrown-with-msg?
       Exception
       #"malformed or duplicate"
       (links/upsert-change-request
        "## Pull requests\n\nNot a link\n"
        pull-request))))

(deftest upsert-change-request-rejects-multiple-links-in-one-entry-line
  (is (thrown-with-msg?
       Exception
       #"malformed or duplicate"
       (links/upsert-change-request
        "## Pull requests\n\n- [one](https://example.com/1) [two](https://example.com/2)\n"
        pull-request))))
