(ns ttt.providers.tracker
  (:require [ttt.providers.tracker.asana :as asana]
            [ttt.providers.tracker.github-issues :as github-issues]
            [ttt.providers.tracker.jira :as jira]
            [ttt.providers.tracker.linear :as linear]))

(def registry
  {:linear {:display-name "Linear"
            :setup-order 0
            :setup-settings [{:key :api-key :label "Linear API key" :env "LINEAR_API_KEY"
                              :required? true :secret? true}
                             {:key :team-id :env "LINEAR_TEAM_ID"}
                             {:key :assignee-id :env "LINEAR_ASSIGNEE_ID"}
                             {:key :workspace-url}
                             {:key :state-id :env "LINEAR_STATE_ID"}
                             {:key :state-name :env "LINEAR_STATE_NAME"}
                             {:key :target-state :env "TTT_TRACKER_STATE"}]
            :build linear/neutral-adapter
            :validate-config! linear/assert-ready!
            :setup linear/setup}
   :jira {:display-name "Jira"
          :setup-order 1
          :setup-settings [{:key :email :label "Jira email" :env "JIRA_EMAIL"
                            :required? true}
                           {:key :api-token :label "Jira API token" :env "JIRA_API_TOKEN"
                            :required? true :secret? true}
                           {:key :site-url :label "Jira site URL" :env "JIRA_SITE_URL"
                            :required? true}
                           {:key :cloud-id :label "Jira cloud ID" :env "JIRA_CLOUD_ID"
                            :required? true}
                           {:key :project :env "JIRA_PROJECT"}
                           {:key :issue-type :env "JIRA_ISSUE_TYPE"}
                           {:key :target-state :env "TTT_TRACKER_STATE"}]
          :build jira/neutral-adapter
          :validate-config! jira/assert-ready!
          :setup jira/setup}
   :github-issues {:display-name "GitHub Issues"
                   :setup-order 2
                   :setup-settings [{:key :target-state :env "TTT_TRACKER_STATE"}]
                   :build github-issues/neutral-adapter
                   :validate-config! github-issues/assert-ready!
                   :setup github-issues/setup}
   :asana {:display-name "Asana"
           :setup-order 3
           :setup-settings [{:key :token :label "Asana token" :env "ASANA_TOKEN"
                             :required? true :secret? true}
                            {:key :workspace :env "ASANA_WORKSPACE"}
                            {:key :base-url}
                            {:key :target-state :env "TTT_TRACKER_STATE"}]
           :build asana/neutral-adapter
           :validate-config! asana/assert-ready!
           :setup asana/setup}})
