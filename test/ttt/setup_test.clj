(ns ttt.setup-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.cli.prompt :as prompt]
            [ttt.cli.setup :as setup]
            [ttt.config :as config]
            [ttt.providers.forge :as forge]
            [ttt.providers.tracker :as tracker]))

(deftest bundled-provider-registries-expose-guided-setup-metadata
  (doseq [[provider-id descriptor] (concat forge/registry tracker/registry)]
    (is (string? (:display-name descriptor)) (name provider-id))
    (is (integer? (:setup-order descriptor)) (name provider-id))
    (is (vector? (:setup-settings descriptor)) (name provider-id))))

(deftest numbered-choice-accepts-enter-for-the-default
  (let [choice (atom nil)]
    (with-out-str
      (reset! choice (with-in-str "\n"
                       (prompt/choose-index 4 "tracker" 2))))
    (is (= 2 @choice))))

(deftest numbered-choice-aborts-on-end-of-input
  (is (thrown-with-msg?
       Exception
       #"Input closed"
       (binding [*out* (java.io.StringWriter.)]
         (with-in-str ""
           (prompt/choose-index 4 "tracker" 2))))))

(deftest secret-input-never-falls-back-to-echoed-input
  (with-redefs [prompt/system-console (constantly nil)]
    (is (thrown-with-msg?
         Exception
         #"Set TEST_TOKEN"
         (prompt/ask-secret "Test token (TEST_TOKEN):" "TEST_TOKEN")))))

(deftest secret-input-uses-the-console-without-echo
  (let [result (atom nil)
        output (with-redefs [prompt/system-console (constantly (Object.))
                             prompt/read-console-password
                             (constantly (char-array "  secret  "))]
                 (with-out-str
                   (reset! result (prompt/ask-secret "Token:" "TEST_TOKEN"))))]
    (is (= "secret" @result))
    (is (not (re-find #"secret" output)))))

(deftest secret-input-aborts-on-end-of-input
  (with-redefs [prompt/system-console (constantly (Object.))
                prompt/read-console-password (constantly nil)]
    (is (thrown-with-msg?
         Exception
         #"Input closed"
         (binding [*out* (java.io.StringWriter.)]
           (prompt/ask-secret "Token:" "TEST_TOKEN"))))))

(deftest unknown-current-provider-requires-an-explicit-choice
  (let [default-index (atom :unset)
        choice (atom nil)]
    (with-redefs [prompt/choose-index (fn [_ _ default]
                                       (reset! default-index default)
                                       0)]
      (with-out-str
        (reset! choice
                (setup/choose-provider
                 :forge
                 {:github {:display-name "GitHub" :setup-order 0}}
                 :removed-provider))))
    (is (nil? @default-index))
    (is (= :github @choice))))

(deftest setup-guides-provider-selection-and-persists-it
  (let [written (atom nil)
        replaced (atom nil)
        environment-secret-used (atom nil)
        configured (atom {})
        setup-fn (fn [role delta]
                   (fn [app-config]
                     (swap! configured assoc role (get-in app-config [role :provider]))
                     delta))]
    (with-redefs [forge/registry
                  {:github {:display-name "GitHub" :setup-order 0}
                   :gitlab {:display-name "GitLab" :setup-order 1
                            :setup-settings [{:key :token :env "SETUP_TEST_TOKEN"
                                              :required? true :secret? true}]
                            :setup (fn [app-config]
                                     (swap! configured assoc :forge
                                            (get-in app-config [:forge :provider]))
                                     (reset! environment-secret-used
                                             (get-in app-config [:forge :token]))
                                     {:forge {:token @environment-secret-used}})}}
                  tracker/registry
                  {:linear {:display-name "Linear" :setup-order 0}
                   :github-issues {:display-name "GitHub Issues" :setup-order 1
                                   :setup-settings []
                                   :setup (setup-fn :tracker
                                                    {:tracker {:target-state "open"}})}}
                  config/load-file-config (constantly {:forge {:provider :github}
                                                  :tracker {:provider :linear}})
                  config/env-overrides (fn [_ _] {:forge {:token "environment-secret"}})
                  prompt/choose-index (fn [_ _ _] 1)
                  config/write-local-config! (fn [value replace-sections]
                                               (reset! written value)
                                               (reset! replaced replace-sections)
                                               "config/ttt.local.edn")]
      (let [output (with-out-str (setup/setup!))]
        (is (= {:forge :gitlab :tracker :github-issues} @configured))
        (is (= {:forge {:provider :gitlab}
                :tracker {:provider :github-issues :target-state "open"}}
               @written))
        (is (= "environment-secret" @environment-secret-used))
        (is (= #{:forge :tracker} @replaced))
        (is (re-find #"GitLab → GitHub Issues" output))
        (is (re-find #"ttt version" output))))))

(deftest switching-providers-discards-the-previous-provider-settings
  (let [descriptor {:setup-settings
                    [{:key :email :env "BITBUCKET_EMAIL"}
                     {:key :api-token :env "BITBUCKET_API_TOKEN" :secret? true}
                     {:key :base-url :env "BITBUCKET_BASE_URL"}]}
        loaded {:forge {:provider :gitlab
                        :token "old-gitlab-token"
                        :base-url "https://gitlab.previous.example"}}]
    (is (= {:provider :bitbucket}
           (setup/selected-role-config loaded :forge :bitbucket descriptor)))))


(deftest setup-collects-only-missing-required-settings
  (with-redefs [prompt/ask (fn [message]
                            ({"Jira email (JIRA_EMAIL):" "alex@example.com"
                              "Jira site URL (JIRA_SITE_URL):" "https://acme.atlassian.net"
                              "Jira cloud ID (JIRA_CLOUD_ID):" "cloud-1"}
                             message))
                prompt/ask-secret (fn [& _] "token")]
    (is (= {:tracker {:email "alex@example.com"
                      :api-token "token"
                      :site-url "https://acme.atlassian.net"
                      :cloud-id "cloud-1"}}
           (setup/collect-required-settings
            {:tracker {:provider :jira}}
            :tracker
            (get tracker/registry :jira))))))
