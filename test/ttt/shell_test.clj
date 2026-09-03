(ns ttt.shell-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.shell :as shell]))

(deftest failure-message-prefers-stderr
  (is (= "gh pr create failed (exit 1): pull request already exists"
         (shell/failure-message ["gh" "pr" "create"]
                                1
                                ""
                                "pull request already exists\nmore detail"))))

(deftest failure-message-falls-back-to-stdout
  (is (= "git command failed (exit 1): stdout detail"
         (shell/failure-message ["git" "command"]
                                1
                                "stdout detail"
                                ""))))
