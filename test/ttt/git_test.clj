(ns ttt.git-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.git :as git]
            [ttt.shell :as shell]))

(deftest slugify-normalizes-title
  (is (= "split-mdps-into-separate-topics-per-data-type"
         (git/slugify "Split MDPs into separate topics per data type"))))

(deftest branch-name-for-issue-builds-linear-style-branch
  (is (= "app-324-split-mdps-into-separate-topics-per-data-type"
         (git/branch-name-for-issue
          {:identifier "APP-324"
           :title "Split MDPs into separate topics per data type"}))))

(deftest branch-name-for-item-prefers-neutral-display-id
  (is (= "app-324-split-mdps"
         (git/branch-name-for-item {:display-id "APP-324" :title "Split MDPs"}))))

(deftest draft-commit-prefers-the-latest-non-merge-commit
  (with-redefs [git/recent-commits (fn []
                                     [{:subject "Merge pull request #120 from org/branch"
                                       :body "merge body"}
                                      {:subject "Add container health checks"
                                       :body "body text"}])]
    (is (= {:subject "Add container health checks"
            :body "body text"}
           (git/draft-commit)))))

(deftest draft-commit-falls-back-to-merge-commit-when-needed
  (with-redefs [git/recent-commits (fn []
                                     [{:subject "Merge branch 'main' into feature"
                                       :body "merge body"}])]
    (is (= {:subject "Merge branch 'main' into feature"
            :body "merge body"}
           (git/draft-commit)))))

(deftest branch-item-identifier-parses-linear-style-branch
  (is (= "APP-467"
         (git/branch-item-identifier "app-467-improve-okx-connectivity-diagnostics"))))

(deftest branch-ticket-id-reads-git-config-when-present
  (with-redefs [shell/run (fn [& args]
                            (is (= ["git" "config" "--local" "--get"
                                    "branch.app-467-foo.ttt.ticket"]
                                   (vec args)))
                            "APP-467")]
    (is (= "APP-467"
           (git/branch-ticket-id "app-467-foo")))))

(deftest branch-ticket-id-returns-nil-when-config-is-missing
  (with-redefs [shell/run (fn [& _]
                            (throw (ex-info "missing"
                                            {:exit 1})))]
    (is (nil? (git/branch-ticket-id "app-467-foo")))))

(deftest set-branch-ticket-id-writes-git-config
  (let [calls (atom nil)]
    (with-redefs [shell/run (fn [& args]
                              (reset! calls (vec args))
                              "")]
      (git/set-branch-ticket-id! "app-467-foo" "APP-467"))
    (is (= ["git" "config" "--local"
            "branch.app-467-foo.ttt.ticket"
            "APP-467"]
           @calls))))
