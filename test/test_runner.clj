(ns test-runner
  (:require [clojure.test :as test]
            [ttt.agent-test]
            [ttt.config-test]
            [ttt.core-test]
            [ttt.fuzzy-test]
            [ttt.git-test]
            [ttt.github-issues-test]
            [ttt.github-test]
            [ttt.gitlab-jira-integration-test]
            [ttt.gitlab-test]
            [ttt.jira-test]
            [ttt.linear-test]
            [ttt.links-test]
            [ttt.main-test]
            [ttt.pr-body-test]
            [ttt.provider-integration-test]
            [ttt.shell-test]
            [ttt.ui-test]
            [ttt.workflow-test]))

(def test-namespaces
  ['ttt.agent-test 'ttt.config-test 'ttt.core-test
   'ttt.fuzzy-test 'ttt.git-test 'ttt.github-issues-test 'ttt.github-test
   'ttt.gitlab-jira-integration-test 'ttt.gitlab-test 'ttt.jira-test
   'ttt.linear-test 'ttt.links-test 'ttt.main-test
   'ttt.pr-body-test 'ttt.provider-integration-test
   'ttt.shell-test 'ttt.ui-test 'ttt.workflow-test])

(defn -main []
  (let [result (apply test/run-tests test-namespaces)]
    (when (pos? (+ (:fail result) (:error result))) (System/exit 1))))
