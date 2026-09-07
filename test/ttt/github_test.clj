(ns ttt.github-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.providers.forge.github :as github]))

(deftest prefix-change-request-title-adds-ticket-prefix
  (is (= "[APP-44] Title"
         (github/prefix-change-request-title "APP-44" "Title"))))

(deftest prefix-change-request-title-replaces-existing-ticket-prefix
  (is (= "[APP-44] Title"
         (github/prefix-change-request-title "APP-44" "[APP-1] Title"))))

(deftest prefix-change-request-title-replaces-bare-ticket-prefix
  (is (= "[APP-44] Title"
         (github/prefix-change-request-title "APP-44" "APP-1 Title"))))

(deftest prefix-change-request-title-replaces-neutral-bracketed-prefix
  (is (= "[#44] Title"
         (github/prefix-change-request-title "#44" "[#1] Title"))))

(deftest normalizes-repositories-and-change-requests-with-neutral-identities
  (let [repo (github/normalize-repo {:nameWithOwner "org/repo"
                                     :defaultBranchRef {:name "main"}})
        change-request (github/normalize-change-request
                        repo
                        {:number 7
                         :title "Retry safely"
                         :body "Body"
                         :url "https://github.com/org/repo/pull/7"
                         :headRefName "retry"
                         :baseRefName "main"})]
    (is (= "org/repo" (:display-id repo)))
    (is (= "org/repo#7" (:display-id change-request)))
    (is (= "retry" (:source-branch change-request)))
    (is (= "main" (:target-branch change-request)))
    (is (domain/same-identity?
         (domain/contained-identity :github :change-request "org/repo" 7)
         (:ref change-request)))))

(deftest inspect-current-normalizes-the-entire-source
  (with-redefs [github/current-branch (fn [] "feature/retry")
                github/current-repo (fn [] {:nameWithOwner "org/repo"
                                             :defaultBranchRef {:name "main"}})
                github/maybe-current-change-request
                (fn [] {:number 7
                         :title "Retry safely"
                         :url "https://github.com/org/repo/pull/7"})]
    (let [source (github/inspect-current)]
      (is (= (domain/identity :github :repository "org/repo")
             (get-in source [:repository :ref])))
      (is (= (domain/contained-identity :github :change-request "org/repo" 7)
             (get-in source [:change-request :ref]))))))

(deftest merged-pull-request-is-not-current
  (with-redefs [ttt.platform.shell/run
                (fn [& _]
                  "{\"number\":4,\"title\":\"Merged\",\"body\":\"\",\"url\":\"https://github.com/org/repo/pull/4\",\"headRefName\":\"branch\",\"baseRefName\":\"main\",\"state\":\"MERGED\"}")]
    (is (nil? (github/maybe-current-change-request)))))

(deftest neutral-adapter-declares-every-forge-capability
  (let [adapter (github/neutral-adapter {})]
    (is (= :github (:provider adapter)))
    (is (= github/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) github/capabilities))))
