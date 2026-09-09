(ns ttt.main-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.cli.main :as main]
            [ttt.cli.workflow :as workflow]))

(deftest parse-args-handles-interactive-options
  (let [parsed (main/parse-args ["--parent" "APP-100" "--yes" "--dry-run"])]
    (is (= "APP-100" (:parent parsed))) (is (:yes parsed)) (is (:dry-run parsed))))

(deftest value-flags-are-not-misread-as-parent
  (let [parsed (main/parse-args ["--interactive" "--title" "Fix bug"])]
    (is (nil? (:parent parsed)))
    (is (= "Fix bug" (:title parsed)))))
(deftest help-publishes-v2-neutral-contract
  (is (str/includes? main/help-text "schema v2"))
  (is (str/includes? main/help-text "item|project|label")))
(deftest llm-guidance-keeps-proposal-ids-internal
  (let [doc (main/llm-doc)]
    (is (str/includes? doc "Keep the proposal ID internal"))
    (is (str/includes? doc "do not ask them to repeat the ID"))))
(deftest execute-builds-one-registry-runtime-and-delegates
  (let [app-config {:tracker {:provider :linear} :forge {:provider :github}} runtime {:config app-config} called (atom nil)]
    (with-redefs [config/load-config (fn [_] app-config) adapters/runtime (fn [cfg _ _] (is (= app-config cfg)) runtime) workflow/execute! (fn [received options] (reset! called [received options]))]
      (main/execute! {:parent "APP-100"}))
    (is (= [runtime {:parent "APP-100"}] @called))))
(deftest connectivity-guidance-does-not-promise-recovery
  (let [writer (java.io.StringWriter.) ex (ex-info "offline" {:kind :github-connectivity})]
    (binding [*err* writer] (main/print-error! ex))
    (is (str/includes? (str writer) "no automatic crash recovery"))))
(deftest run-agent-prints-a-v2-envelope
  (with-redefs [agent/run (fn [_] {:exit 0 :envelope {:schemaVersion 2 :ok true :command "version" :data {:agentApiVersion 2}}})]
    (let [result (json/parse-string (with-out-str (main/run-agent! ["version"])) true)]
      (is (= 2 (:schemaVersion result))) (is (= 2 (get-in result [:data :agentApiVersion]))))))


(deftest human-output-covers-every-machine-command
  (let [payloads {"version" {:name "ttt" :version "0.3.0" :agentApiVersion 2 :capabilities ["inspect" "search"]}
                  "inspect" {:branch "agent/human-output" :repository {:displayId "owner/repo" :url "https://example.test/repo"}}
                  "search" {:kind "item" :query "human output" :candidates [{:displayId "APP-123" :title "Human output"}]}
                  "preview" {:proposalId "lp2_preview" :changeRequestIntent {:title "Human output" :draft false}}
                  "apply" {:proposalId "lp2_apply" :changeRequest {:displayId "owner/repo#1" :url "https://example.test/pr/1"}}}
        expected {"version" ["Name: ttt" "Version: 0.3.0" "Agent API version: 2" "  - inspect"]
                  "inspect" ["Branch: agent/human-output" "Repository:" "Display ID: owner/repo"]
                  "search" ["Kind: item" "Candidates:" "Display ID: APP-123"]
                  "preview" ["Proposal ID: lp2_preview" "Change request intent:" "Draft: false"]
                  "apply" ["Proposal ID: lp2_apply" "Change request:" "URL: https://example.test/pr/1"]}]
    (is (= main/machine-commands (set (keys payloads))))
    (doseq [[command data] payloads]
      (with-redefs [agent/run (fn [args]
                                (is (= [command] (vec args)))
                                {:exit 0 :envelope {:schemaVersion 2 :ok true :command command :data data}})]
        (let [output (with-out-str (main/run-agent! [command "--human"]))]
          (doseq [text (get expected command)] (is (str/includes? output text)))
          (is (not (str/starts-with? output "{"))))))))

(deftest human-output-keeps-all-error-details
  (with-redefs [agent/run (fn [_] {:exit 2 :envelope {:schemaVersion 2 :ok false :command "search"
                                                       :error {:code "remote-api-error" :message "Request failed"
                                                               :provider "jira" :status 503 :details "Unavailable"}}})]
    (let [output (with-out-str (main/run-agent! ["search" "--human"]))]
      (doseq [text ["Code: remote-api-error" "Message: Request failed" "Provider: jira" "Status: 503" "Details: Unavailable"]]
        (is (str/includes? output text))))))

(deftest command-help-preempts-command-execution
  (let [called? (atom false)
        output (with-redefs [agent/run (fn [_] (reset! called? true))]
                 (with-out-str (main/-main "version" "--help")))]
    (is (str/includes? output "Usage: ttt version [--human]"))
    (is (false? @called?))))

(deftest help-command-shows-every-command
  (doseq [command (keys main/command-usages)]
    (is (str/includes? (with-out-str (main/-main "help" command))
                       (str "Usage: ttt " command)))))
