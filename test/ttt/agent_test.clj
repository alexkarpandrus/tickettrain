(ns ttt.agent-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.domain :as domain]))

(def scope (domain/scope-identity :linear "team-1"))
(def item {:ref (domain/identity :linear :tracker-item "issue-1") :display-id "APP-123" :title "Retry" :description "Description" :url "https://linear/item" :scopes [scope] :labels []})
(def source {:branch "retry" :repository {:ref (domain/identity :github :repository "org/repo") :display-id "org/repo"} :change-request {:ref (domain/contained-identity :github :change-request "org/repo" 7) :display-id "org/repo#7" :title "Retry" :body "Body" :url "https://github/pr"}})
(def config {:change-request {:body-begin-marker "<!-- ttt:begin -->" :body-end-marker "<!-- ttt:end -->" :section-title "Tracker"}})
(defn runtime [calls]
  {:config config :forge {:inspect-current (fn [] source) :prefix-change-request-title (fn [id title] (str "[" id "] " title)) :update-change-request! (fn [& _] (swap! calls conj :forge))
                          :create-change-request! (fn [intent] (swap! calls conj :forge-create) (assoc (:change-request source) :title (:title intent) :body (:body intent)))
                          :identify-change-request (fn [_ created] created)}
   :tracker {:configured-scope (fn [] scope) :resolve-item (fn [ref] (when (= ref "APP-123") item)) :resolve-parent-item (fn [_] nil) :resolve-project (fn [_] nil) :resolve-labels (fn [_ _] []) :search-parent-items (fn [] [item]) :search-projects (fn [] []) :search-labels (fn [] []) :update-item! (fn [resolved _] (swap! calls conj :tracker) resolved) :create-item! (fn [& _] (swap! calls conj :tracker-create) item)}})

(deftest numeric-text-options-remain-strings
  (is (= {:query "1218235721923599" :project "1182987059881499"}
         (agent/parse-options ["--query" "1218235721923599"
                               "--project" "1182987059881499"]))))

(deftest item-search-returns-an-exact-reference
  (let [calls (atom [])
        candidate (first (get-in (agent/search-data (:tracker (runtime calls))
                                                    {:kind "item" :query "APP-123"})
                                 [:candidates]))]
    (is (= "APP-123" (:displayId candidate)))
    (is (= 1.0 (:score candidate)))))

(deftest version-advertises-product-and-agent-api-versions
  (let [version (agent/execute-command "version" [])]
    (is (= (str/trim (slurp "version.txt")) (:version version)))
    (is (= 2 (:agentApiVersion version)))
    (is (some #{"create-change-request"} (:capabilities version)))))

(deftest status-shows-providers-and-sources-without-secret-values
  (with-redefs [config/load-config (fn [_]
                                     {:forge {:provider :gitlab :token "forge-secret"}
                                      :tracker {:provider :jira :email "dev@example.com" :api-token "tracker-secret"}})]
    (let [status (agent/status-data {:config "/tmp/ttt.edn"})]
      (is (= {:provider "gitlab" :configuredSettings ["token"]} (:forge status)))
      (is (= {:provider "jira" :configuredSettings ["api-token" "email"]} (:tracker status)))
      (is (= "/tmp/ttt.edn" (get-in status [:sources :baseConfig :path])))
      (is (not (str/includes? (pr-str status) "secret"))))))
(deftest preview-is-read-only-and-uses-neutral-wire-fields
  (let [calls (atom [])
        runtime* (assoc-in (runtime calls) [:config :tracker :target-state] "In Progress")
        proposal (agent/preview-data runtime* {:action "link_existing" :item "APP-123" :labels []})]
    (is (empty? @calls))
    (is (.startsWith (:proposalId proposal) "lp2_"))
    (is (contains? proposal :changeRequestUpdate))
    (is (not (contains? proposal :linearDescription)))
    (is (= "7" (get-in proposal [:source :changeRequest :identity :id])))
    (is (= {:provider "linear" :kind "scope" :id "team-1"}
           (get-in proposal [:approvalContext :trackerScope])))
    (is (= "In Progress"
           (get-in proposal [:approvalContext :trackerSettings :targetState])))))


(deftest approval-context-matches-linear-legacy-state-precedence
  (let [calls (atom [])
        runtime* (-> (runtime calls)
                     (assoc-in [:config :tracker :state-name] "Todo")
                     (assoc-in [:config :tracker :state-id] "done-id"))
        proposal (agent/preview-data runtime* {:action "link_existing" :item "APP-123" :labels []})]
    (is (= "done-id"
           (get-in proposal [:approvalContext :trackerSettings :targetState])))))
(deftest mismatched-proposal-is-rejected-before-mutation
  (let [calls (atom [])]
    (is (thrown-with-msg? Exception #"Approval does not match" (agent/apply-data! (runtime calls) {:action "link_existing" :item "APP-123" :labels []} "lp2_wrong")))
    (is (empty? @calls))))


(deftest configuration-change-invalidates-approval
  (let [calls (atom [])
        request {:action "create_new" :title "Retry" :labels []}
        preview-runtime (assoc-in (runtime calls) [:config :tracker :team-id] "team-1")
        apply-runtime (assoc-in preview-runtime [:config :tracker :team-id] "team-2")
        approval (:proposalId (agent/preview-data preview-runtime request))]
    (is (thrown-with-msg? Exception #"Approval does not match"
                          (agent/apply-data! apply-runtime request approval)))
    (is (empty? @calls))))


(deftest preview-does-not-emit-configuration-secrets
  (let [calls (atom [])
        runtime* (assoc-in (runtime calls) [:config :tracker :api-token] "secret-value")
        proposal (agent/preview-data runtime* {:action "link_existing" :item "APP-123" :labels []})]
    (is (not (str/includes? (pr-str proposal) "secret-value")))))
(deftest apply-routes-through-core-in-order
  (let [calls (atom []) runtime* (runtime calls) request {:action "link_existing" :item "APP-123" :labels []} approval (:proposalId (agent/preview-data runtime* request))]
    (agent/apply-data! runtime* request approval)
    (is (= [:tracker :forge] @calls))))
(deftest direct-item-outside-scope-is-rejected-at-the-agent-edge
  (let [calls (atom []) runtime* (assoc-in (runtime calls) [:tracker :resolve-item] (fn [_] (assoc item :scopes [(domain/scope-identity :linear "team-2")])))]
    (is (thrown-with-msg? Exception #"outside the configured scope" (agent/preview-data runtime* {:action "link_existing" :item "APP-999" :labels []})))
    (is (empty? @calls))))

(deftest request-validation-rejects-v1-and-malformed-input
  (is (thrown-with-msg? Exception #"requires item" (agent/validate-request! {:action "link_existing" :issue "APP-123"})))
  (is (thrown-with-msg? Exception #"must be a string" (agent/validate-request! {:action "link_existing" :item 1 :labels []}))))

(deftest create-new-allows-no-parent-or-project
  (is (= {:action "create_new" :labels []}
         (agent/validate-request! {:action "create_new" :labels []}))))


(deftest standalone-change-request-validation
  (is (= {:action "create_change_request" :title "Docs" :body "Body"}
         (agent/validate-request! {:action "create_change_request" :title "Docs" :body "Body"})))
  (is (thrown-with-msg? Exception #"requires title"
                        (agent/validate-request! {:action "create_change_request"})))
  (is (thrown-with-msg? Exception #"accepts only title and body"
                        (agent/validate-request! {:action "create_change_request" :title "Docs" :labels []}))))

(deftest standalone-change-request-ignores-every-tracker-provider
  (doseq [tracker [:linear :jira :github-issues :asana]]
    (let [built-roles (atom [])]
      (with-redefs [config/load-config (fn [_] {:forge {:provider :github}
                                                :tracker {:provider tracker}})
                    adapters/build (fn [_ role _]
                                     (swap! built-roles conj role)
                                     {:provider :github})]
        (is (= #{:config :forge}
               (set (keys (agent/request-runtime {} {:action "create_change_request"}))))
            (name tracker))
        (is (= [:forge] @built-roles) (name tracker))))))

(deftest standalone-change-request-needs-no-tracker
  (let [calls (atom [])
        source* (assoc source :change-request nil
                       :repository (assoc (:repository source) :default-target-branch "main"))
        runtime* (-> (runtime calls)
                     (dissoc :tracker)
                     (assoc-in [:forge :inspect-current] (fn [] source*)))
        request {:action "create_change_request" :title "Docs" :body "PR body"}
        proposal (agent/preview-data runtime* request)
        result (agent/apply-data! runtime* request (:proposalId proposal))]
    (is (= {:title "Docs" :body "PR body" :base "main" :head "retry"}
           (:changeRequestIntent proposal)))
    (is (= request (:request proposal)))
    (is (not (contains? proposal :trackerIntent)))
    (is (= [:forge-create] @calls))
    (is (= "https://github/pr" (get-in result [:changeRequest :url])))))


(deftest standalone-change-request-rejects-an-existing-one
  (let [calls (atom [])
        request {:action "create_change_request" :title "Docs"}]
    (is (thrown-with-msg? Exception #"already has a change request"
                          (agent/preview-data (dissoc (runtime calls) :tracker) request)))
    (is (empty? @calls))))

(deftest approved-create-new-opens-a-missing-change-request-before-linking-back
  (let [calls (atom [])
        source* (assoc source :change-request nil
                       :repository (assoc (:repository source) :default-target-branch "main"))
        runtime* (-> (runtime calls)
                     (assoc-in [:forge :inspect-current] (fn [] source*)))
        request {:action "create_new" :title "Retry" :labels []}
        proposal (agent/preview-data runtime* request)
        result (agent/apply-data! runtime* request (:proposalId proposal))]
    (is (not (str/includes? (get-in proposal [:trackerIntent :description]) "]()")))
    (is (= [:tracker-create :forge-create :tracker] @calls))
    (is (= "https://github/pr" (get-in result [:changeRequest :url])))))

(deftest search-and-preview-carry-state-and-project
  (let [calls (atom [])
        project {:ref (domain/identity :linear :project "project-1") :display-id "reliability" :title "Reliability" :url "https://linear/project"}
        stateful (assoc item :state {:name "In Progress" :type "started"} :project project)
        base (runtime calls)
        candidates (get-in (agent/search-data (assoc (:tracker base) :search-parent-items (fn [] [stateful]))
                                              {:kind "item" :query "Retry"})
                           [:candidates])]
    (is (= "In Progress" (get-in candidates [0 :state :name])))
    (is (= "https://linear/project" (get-in candidates [0 :project :url])))
    (is (= "reliability" (get-in candidates [0 :project :displayId])))
    (let [preview-runtime (assoc-in base [:tracker :resolve-item] (fn [ref] (when (= ref "APP-123") stateful)))
          proposal (agent/preview-data preview-runtime {:action "link_existing" :item "APP-123" :labels []})]
      (is (= "started" (get-in proposal [:item :state :type])))
      (is (= "Reliability" (get-in proposal [:item :project :title]))))))
