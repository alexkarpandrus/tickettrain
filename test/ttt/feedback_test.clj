(ns ttt.feedback-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.feedback :as feedback]
            [ttt.providers.forge :as forge]))

(deftest shared-request-validation-accepts-native-opaque-identities-not-untyped-values
  (doseq [id ["123" "PRRC_native" "{00000000-0000-0000-0000-000000000004}"]]
    (let [request {:action "review_change_request" :changeRequest "7" :batchId "native-ids"
                   :operations [{:type "edit_note" :discussion "native-thread" :note id :body "Text"}
                                {:type "update_reviewers" :reviewers [id]}]}]
      (is (= request (feedback/validate-request! request)))))
  (doseq [value [nil "" " " 7 [] {}]]
    (is (= :invalid-request
           (try (feedback/validate-operation! {:type "edit_note" :discussion "thread" :note value :body "Text"})
                (catch Exception ex (:code (ex-data ex)))))))
  (doseq [id ["opaque-pr-id" "0" "-7" 7]]
    (is (= :invalid-request
           (try (feedback/validate-request! {:action "review_change_request" :changeRequest id :batchId "check"
                                            :operations [{:type "reply" :discussion "thread" :body "Text"}]})
                (catch Exception ex (:code (ex-data ex))))))))

(deftest every-bundled-forge-declares-the-same-four-feedback-operations
  (let [version (agent/execute-command "version" [])]
    (doseq [[provider descriptor] forge/registry]
      (let [adapter ((:build descriptor) {:forge {:provider provider}})]
        (is (= adapters/feedback-capabilities (:feedback-capabilities adapter)))
        (is (= adapter (adapters/assert-capabilities! :forge adapter)))
        (is (= ["edit_note" "reply" "resolve_discussion" "update_reviewers"]
               (get-in version [:forgeFeedbackCapabilities (name provider)])))))))

(deftest discussion-only-adapters-do-not-need-reviewer-management-functions
  (let [adapter ((get-in forge/registry [:gitlab :build]) {:forge {:provider :gitlab}})
        discussion-only (-> adapter
                            (assoc :feedback-capabilities #{:reply :edit-note :resolve-discussion})
                            (update :capabilities disj :resolve-reviewer)
                            (dissoc :resolve-reviewer))]
    (is (= discussion-only (adapters/assert-capabilities! :forge discussion-only)))
    (is (thrown? Exception (adapters/assert-capabilities! :forge
                                                         (assoc discussion-only :feedback-capabilities #{:update-reviewers}))))
    (is (thrown? Exception (adapters/assert-capabilities! :forge (dissoc discussion-only :apply-feedback!))))
    (is (thrown? Exception (adapters/assert-capabilities! :forge (assoc discussion-only :feedback-capabilities #{:unknown}))))))

(deftest note-metadata-does-not-disappear-when-an-author-is-deleted
  (let [note {:ref {:provider :github :kind :note :container "repo#7" :id "PRRC_note"}
              :body "Review" :author nil :created-at "created" :updated-at "updated"
              :system? false :position {:path "file.clj" :line 7}}
        wire (agent/wire-feedback-entity note)]
    (is (= "Review" (:body wire)))
    (is (= "created" (:createdAt wire)))
    (is (= "updated" (:updatedAt wire)))
    (is (false? (:system wire)))
    (is (= {:path "file.clj" :line 7} (:position wire)))))
