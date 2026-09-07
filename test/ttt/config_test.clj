(ns ttt.config-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.config :as config]))

(deftest dotenv-values-override-config
  (let [merged (-> (config/deep-merge
                    {:tracker {:provider :linear
                               :api-key "placeholder"
                               :team-id "placeholder"
                               :assignee-id "self"
                               :state-name "In Review"
                               :workspace-url "https://linear.app/YOUR_WORKSPACE"}}
                    (config/env-overrides
                     {"LINEAR_API_KEY" "token"
                      "LINEAR_TEAM_ID" "team"
                      "LINEAR_ASSIGNEE_ID" "user-123"
                      "LINEAR_STATE_NAME" "Review"
                      "LINEAR_WORKSPACE" "acme"}))
                   config/normalize-config)]
    (is (= :linear (get-in merged [:tracker :provider])))
    (is (= "token" (get-in merged [:tracker :api-key])))
    (is (= "team" (get-in merged [:tracker :team-id])))
    (is (= "user-123" (get-in merged [:tracker :assignee-id])))
    (is (= "Review" (get-in merged [:tracker :state-name])))
    (is (= "https://linear.app/acme" (get-in merged [:tracker :workspace-url])))))

(deftest dotenv-overrides-preserve-a-configured-provider
  (let [merged (-> (config/deep-merge
                    {:tracker {:provider :example-tracker}
                     :forge {:provider :example-forge}}
                    (config/env-overrides {"LINEAR_API_KEY" "token"}))
                   config/normalize-config)]
    (is (= :example-tracker (get-in merged [:tracker :provider])))
    (is (= :example-forge (get-in merged [:forge :provider])))
    (is (= "token" (get-in merged [:tracker :api-key])))))

(deftest tracker-provider-selects-managed-section-title
  (is (= "GitHub Issues"
         (get-in (config/normalize-config {:tracker {:provider :github-issues}})
                 [:change-request :section-title]))))

(deftest jira-credentials-map-from-the-environment
  (is (= {:email "alex@example.com"
          :api-token "jira-token"
          :site-url "https://acme.atlassian.net"
          :cloud-id "cloud-1"
          :project "APP"
          :issue-type "Task"}
         (:tracker (config/env-overrides
                    {"JIRA_EMAIL" "alex@example.com"
                     "JIRA_API_TOKEN" "jira-token"
                     "JIRA_SITE_URL" "https://acme.atlassian.net"
                     "JIRA_CLOUD_ID" "cloud-1"
                     "JIRA_PROJECT" "APP"
                     "JIRA_ISSUE_TYPE" "Task"})))))

(deftest gitlab-credentials-map-from-the-environment
  (is (= {:token "gitlab-token" :base-url "https://gitlab.example.com"}
         (:forge (config/env-overrides
                  {"GITLAB_TOKEN" "gitlab-token"
                   "GITLAB_BASE_URL" "https://gitlab.example.com"})))))

(deftest normalize-config-fills-default-provider-sections
  (let [normalized (config/normalize-config
                    {:tracker {:provider :linear
                               :api-key "token"
                               :team-id "team-1"}
                     :change-request {:body-begin-marker "<!-- begin -->"
                                      :body-end-marker "<!-- end -->"
                                      :section-title "Tracker"}})]
    (is (= :linear (get-in normalized [:tracker :provider])))
    (is (= :github (get-in normalized [:forge :provider])))
    (is (= "Tracker" (get-in normalized [:change-request :section-title])))))

(deftest generic-provider-settings-reject-missing-and-placeholder-values
  (is (thrown-with-msg?
       Exception
       #"linear config is missing values"
       (config/assert-settings! :linear
                                {:api-key "YOUR_LINEAR_API_KEY" :team-id nil}
                                [:api-key :team-id])))
  (is (nil? (config/assert-settings! :example {:token "ready"} [:token]))))

(deftest adapter-build-rejects-unknown-providers
  (is (thrown-with-msg?
       Exception
       #"Unsupported forge provider"
       (adapters/build {:forge {:provider :missing}} :forge {}))))

(deftest adapter-build-rejects-malformed-descriptors
  (doseq [registry [{:broken {:build :not-callable}}
                    {:broken {:build (fn [_] {:provider :broken})
                              :validate-config! :not-callable}}]]
    (is (thrown-with-msg?
         Exception
         #"descriptor"
         (adapters/build {:forge {:provider :broken}} :forge registry)))))

(deftest adapter-build-rejects-provider-mismatches
  (is (thrown-with-msg?
       Exception
       #"does not match"
       (adapters/build {:forge {:provider :broken}}
                       :forge
                       {:broken {:build (fn [_]
                                          {:provider :different
                                           :capabilities (get adapters/required-capabilities :forge)})}}))))

(deftest adapter-build-rejects-missing-capabilities
  (let [registry {:broken {:build (fn [_]
                                    {:provider :broken
                                     :capabilities #{}})}}]
    (is (thrown-with-msg?
         Exception
         #"missing required capabilities"
         (adapters/build {:forge {:provider :broken}} :forge registry)))))

(deftest load-config-merges-system-env-without-throwing
  (let [tmp (java.io.File/createTempFile "ttt-config" ".edn")]
    (spit tmp "{:tracker {:provider :linear :api-key \"k\" :team-id \"t\" :workspace-url \"https://linear.app/acme\"}}")
    (try
      (is (= :linear (get-in (config/load-config (.getPath tmp)) [:tracker :provider])))
      (finally (.delete tmp)))))

(deftest real-environment-overrides-dotenv
  (is (= {"LINEAR_API_KEY" "real"}
         (config/merge-env-config {"LINEAR_API_KEY" "dotenv"} {"LINEAR_API_KEY" "real"})))
  (is (= {"LINEAR_API_KEY" "dotenv"} (config/merge-env-config {"LINEAR_API_KEY" "dotenv"} {}))))
