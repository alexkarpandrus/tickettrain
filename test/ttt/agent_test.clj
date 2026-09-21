(ns ttt.agent-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.inference.typesafe :as typesafe]))

(def scope (domain/scope-identity :linear "team-1"))
(def item {:ref (domain/identity :linear :tracker-item "issue-1") :display-id "APP-123" :title "Retry" :description "Description" :url "https://linear/item" :scopes [scope] :labels []})
(def source {:branch "retry" :repository {:ref (domain/identity :github :repository "org/repo") :display-id "org/repo"} :change-request {:ref (domain/contained-identity :github :change-request "org/repo" 7) :display-id "org/repo#7" :title "Retry" :body "Body" :url "https://github/pr"}})
(def config {:change-request {:body-begin-marker "<!-- ttt:begin -->" :body-end-marker "<!-- ttt:end -->" :section-title "Tracker"}})
(defn runtime [calls]
  {:config config :forge {:inspect-current (fn [] source) :prefix-change-request-title (fn [id title] (str "[" id "] " title)) :update-change-request! (fn [& _] (swap! calls conj :forge)) :comment-change-request! (fn [& _] (swap! calls conj :forge-comment))
                          :create-change-request! (fn [intent] (swap! calls conj :forge-create) (assoc (:change-request source) :title (:title intent) :body (:body intent)))
                          :identify-change-request (fn [_ created] created)}
   :tracker {:configured-scope (fn [] scope) :resolve-item (fn [ref] (when (= ref "APP-123") item)) :resolve-parent-item (fn [_] nil) :resolve-project (fn [_] nil) :resolve-labels (fn [_ _] []) :search-parent-items (fn [] [item]) :search-projects (fn [] []) :search-labels (fn [] []) :update-item! (fn [resolved _] (swap! calls conj :tracker) resolved) :create-item! (fn [& _] (swap! calls conj :tracker-create) item) :comment-item! (fn [& _] (swap! calls conj :tracker-comment))}})

(deftest numeric-text-options-remain-strings
  (is (= {:profile "client" :query "1218235721923599" :project "1182987059881499"}
         (agent/parse-options ["--profile" "client"
                               "--query" "1218235721923599"
                               "--project" "1182987059881499"]))))

(deftest item-search-returns-an-exact-reference
  (let [calls (atom [])
        candidate (first (get-in (agent/search-data (:tracker (runtime calls))
                                                    {:kind "item" :query "APP-123"})
                                 [:candidates]))]
    (is (= "APP-123" (:displayId candidate)))
    (is (= 1.0 (:score candidate)))))

(deftest list-items-filters-normalized-fields-and-search-includes-labels
  (let [project {:ref (domain/identity :linear :project "project-x")
                 :display-id "Project X"
                 :title "Project X"}
        label {:ref (domain/identity :linear :label "waiting")
               :display-id "waiting"}
        matching (assoc item :state {:name "pending"} :project project :labels [label])
        other (assoc item :ref (domain/identity :linear :tracker-item "issue-2")
                     :display-id "APP-456" :state {:name "done"})
        tracker* {:configured-scope (constantly scope)
                  :list-items (fn [] [other matching])
                  :resolve-item (constantly nil)
                  :search-parent-items (fn [] [matching])}
        listed (first (:items (agent/list-data tracker* {:kind "item"
                                                         :state "PENDING"
                                                         :project "project-x"
                                                         :label "WAITING"})))
        searched (first (:candidates (agent/search-data tracker* {:kind "item" :query "Retry"})))]
    (is (= 50 (agent/bounded-list-limit nil)))
    (is (= "APP-123" (:displayId listed)))
    (is (= "Description" (:description listed)))
    (is (= "pending" (get-in listed [:state :name])))
    (is (= "Project X" (get-in listed [:project :displayId])))
    (is (= "waiting" (get-in listed [:labels 0 :displayId])))
    (is (= "waiting" (get-in searched [:labels 0 :displayId])))))

(deftest semantic-search-reranks-before-applying-the-output-limit
  (let [calls (atom [])
        other (assoc item
                     :ref (domain/identity :linear :tracker-item "issue-2")
                     :display-id "APP-456"
                     :title "Stripe retries")
        tracker* (assoc (:tracker (runtime calls)) :search-parent-items (fn [] [item other]))]
    (with-redefs [typesafe/rerank (fn [query candidates]
                                    (is (= "payment recovery" query))
                                    (is (= 2 (count candidates)))
                                    {:candidates (vec (reverse candidates))
                                     :ranking {:method "jev" :model "jev-1.13.0" :confidence 0.8}})]
      (let [result (agent/search-data tracker* {:kind "item" :query "payment recovery"
                                                :semantic true :limit 1})]
        (is (= "APP-456" (get-in result [:candidates 0 :displayId])))
        (is (= "jev" (get-in result [:ranking :method])))))))

(deftest semantic-search-falls-back-without-losing-candidates
  (let [candidates [{:displayId "APP-1"} {:displayId "APP-2"}]]
    (with-redefs [typesafe/rerank (fn [_ _]
                                    (throw (ex-info "offline" {:code :provider-unavailable})))]
      (let [result (agent/semantic-search "retry" candidates)]
        (is (= candidates (:candidates result)))
        (is (= {:method "lexical" :fallbackReason "provider-unavailable"}
               (:ranking result)))))))

(deftest version-advertises-product-and-agent-api-versions
  (let [version (agent/execute-command "version" [])]
    (is (= (str/trim (slurp "version.txt")) (:version version)))
    (is (= 2 (:agentApiVersion version)))
    (is (some #{"create-change-request"} (:capabilities version)))
    (is (some #{"create-items"} (:capabilities version)))
    (is (some #{"update-items"} (:capabilities version)))
    (is (some #{"update-change-requests"} (:capabilities version)))
    (is (some #{"named-profiles"} (:capabilities version)))
    (is (some #{"comment-items"} (:capabilities version)))
    (is (some #{"list-items"} (:capabilities version)))
    (is (some #{"comment-change-requests"} (:capabilities version)))))

(deftest status-shows-providers-and-sources-without-secret-values
  (with-redefs [config/load-config (fn [_ profile]
                                     (is (= "client" profile))
                                     {:profile :client
                                      :forge {:provider :gitlab :token "forge-secret"}
                                      :tracker {:provider :jira :email "dev@example.com" :api-token "tracker-secret"}})]
    (let [status (agent/status-data {:config "/tmp/ttt.edn" :profile "client"})]
      (is (= "client" (:profile status)))
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

(deftest selected-profile-is-visible-and-bound-to-the-proposal
  (let [calls (atom [])
        request {:action "link_existing" :item "APP-123" :labels []}
        work-proposal (agent/preview-data (assoc-in (runtime calls) [:config :profile] :work) request)
        client-proposal (agent/preview-data (assoc-in (runtime calls) [:config :profile] :client) request)]
    (is (= "work" (:profile work-proposal)))
    (is (not= (:proposalId work-proposal) (:proposalId client-proposal)))))


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

(deftest standalone-item-validation
  (is (= {:action "create_item" :title "Status" :description "Follow up" :project "Work" :labels ["waiting"]}
         (agent/validate-request! {:action "create_item" :title "Status" :description "Follow up" :project "Work" :labels ["waiting"]})))
  (is (thrown-with-msg? Exception #"requires title"
                        (agent/validate-request! {:action "create_item"})))
  (is (thrown-with-msg? Exception #"labels must not be blank"
                        (agent/validate-request! {:action "create_item" :title "Status" :labels [""]}))))

(deftest standalone-item-preview-and-apply-need-no-forge
  (let [calls (atom [])
        runtime* (dissoc (runtime calls) :forge)
        request {:action "create_item" :title "Status" :description "Follow up" :labels []}
        proposal (agent/preview-data runtime* request)
        result (agent/apply-data! runtime* request (:proposalId proposal))]
    (is (= request (:request proposal)))
    (is (= "Status" (get-in proposal [:trackerIntent :title])))
    (is (= "Follow up" (get-in proposal [:trackerIntent :description])))
    (is (= [:tracker-create] @calls))
    (is (= "APP-123" (get-in result [:item :displayId])))))

(deftest standalone-item-update-preview-is-exact-and-apply-is-gated
  (let [calls (atom [])
        bug {:ref (domain/identity :linear :label "bug") :display-id "Bug" :scopes [scope]}
        runtime* (-> (runtime calls)
                     (dissoc :forge)
                     (assoc-in [:tracker :resolve-labels]
                               (fn [refs _] (if (= ["Bug"] refs) [bug] [])))
                     (assoc-in [:tracker :update-item!]
                               (fn [resolved intent]
                                 (swap! calls conj :tracker)
                                 (assoc resolved :labels (:labels intent)))))
        request {:action "update_item" :item "APP-123" :comment "Jade replied." :addLabels ["Bug"] :removeLabels []}
        proposal (agent/preview-data runtime* request)]
    (is (empty? @calls))
    (is (= request (:request proposal)))
    (is (= "APP-123" (get-in proposal [:item :displayId])))
    (is (= "Jade replied." (get-in proposal [:comment :body])))
    (is (= ["Bug"] (mapv :displayId (get-in proposal [:labelChanges :add]))))
    (is (empty? (get-in proposal [:labelChanges :remove])))
    (is (thrown-with-msg? Exception #"Approval does not match"
                          (agent/apply-data! runtime* request "lp2_wrong")))
    (is (empty? @calls))
    (agent/apply-data! runtime* request (:proposalId proposal))
    (is (= [:tracker :tracker-comment] @calls))))

(deftest standalone-item-update-validation
  (is (thrown-with-msg? Exception #"requires item"
                        (agent/validate-request! {:action "update_item" :comment "Note"})))
  (is (thrown-with-msg? Exception #"requires comment, addLabels, or removeLabels"
                        (agent/validate-request! {:action "update_item" :item "APP-123"})))
  (is (thrown-with-msg? Exception #"must be a collection of strings"
                        (agent/validate-request! {:action "update_item" :item "APP-123" :addLabels "waiting"}))))


(deftest standalone-change-request-validation
  (is (= {:action "create_change_request" :title "Docs" :body "Body"}
         (agent/validate-request! {:action "create_change_request" :title "Docs" :body "Body"})))
  (is (thrown-with-msg? Exception #"requires title"
                        (agent/validate-request! {:action "create_change_request"})))
  (is (thrown-with-msg? Exception #"accepts only title and body"
                        (agent/validate-request! {:action "create_change_request" :title "Docs" :labels []}))))

(deftest update-change-request-validation
  (is (= {:action "update_change_request" :body "Body"}
         (agent/validate-request! {:action "update_change_request" :body "Body"})))
  (is (= {:action "update_change_request" :title "New title"}
         (agent/validate-request! {:action "update_change_request" :title "New title"})))
  (is (thrown-with-msg? Exception #"requires title or body"
                        (agent/validate-request! {:action "update_change_request"})))
  (is (thrown-with-msg? Exception #"title must not be blank"
                        (agent/validate-request! {:action "update_change_request" :title " "})))
  (is (thrown-with-msg? Exception #"accepts only title and body"
                        (agent/validate-request! {:action "update_change_request" :body "Body" :labels []}))))

(deftest comment-request-validation
  (is (= {:action "comment_item" :item "APP-123" :body "Note"}
         (agent/validate-request! {:action "comment_item" :item "APP-123" :body "Note"})))
  (is (= {:action "comment_change_request" :body "Note"}
         (agent/validate-request! {:action "comment_change_request" :body "Note"})))
  (is (thrown-with-msg? Exception #"requires body"
                        (agent/validate-request! {:action "comment_item" :item "APP-123" :body " "})))
  (is (thrown-with-msg? Exception #"accepts only body"
                        (agent/validate-request! {:action "comment_change_request" :body "Note" :item "APP-123"}))))

(deftest approved-comments-route-to-only-the-selected-provider
  (let [calls (atom [])
        runtime* (runtime calls)
        item-request {:action "comment_item" :item "APP-123" :body "Tracker note"}
        item-proposal (agent/preview-data runtime* item-request)]
    (is (empty? @calls))
    (is (= "Tracker note" (get-in item-proposal [:comment :body])))
    (agent/apply-data! runtime* item-request (:proposalId item-proposal))
    (is (= [:tracker-comment] @calls))
    (reset! calls [])
    (let [forge-request {:action "comment_change_request" :body "PR note"}
          forge-proposal (agent/preview-data runtime* forge-request)]
      (is (= "org/repo#7" (get-in forge-proposal [:changeRequest :displayId])))
      (agent/apply-data! runtime* forge-request (:proposalId forge-proposal))
      (is (= [:forge-comment] @calls)))))

(deftest standalone-change-request-ignores-every-tracker-provider
  (doseq [tracker [:linear :jira :github-issues :asana :taskwarrior]]
    (let [built-roles (atom [])]
      (with-redefs [config/load-config (fn [_ _ roles]
                                         (is (= [:forge] roles))
                                         {:forge {:provider :github}
                                          :tracker {:provider tracker}})
                    adapters/build (fn [_ role _]
                                     (swap! built-roles conj role)
                                     {:provider :github})]
        (is (= #{:config :forge}
               (set (keys (agent/request-runtime {} {:action "create_change_request"}))))
            (name tracker))
        (is (= [:forge] @built-roles) (name tracker))))))

(deftest update-change-request-needs-no-tracker
  (let [built-roles (atom [])]
    (with-redefs [config/load-config (fn [_ _ roles]
                                       (is (= [:forge] roles))
                                       {:forge {:provider :github}})
                  adapters/build (fn [_ role _]
                                   (swap! built-roles conj role)
                                   {:provider :github})]
      (is (= #{:config :forge}
             (set (keys (agent/request-runtime {} {:action "update_change_request"})))))
      (is (= [:forge] @built-roles)))))

(deftest approved-change-request-update-is-exact-and-forge-only
  (let [calls (atom [])
        runtime* (assoc-in (runtime calls) [:forge :update-change-request!]
                           (fn [& args] (swap! calls conj args)))
        request {:action "update_change_request" :body "Updated body"}
        proposal (agent/preview-data runtime* request)
        result (agent/apply-data! runtime* request (:proposalId proposal))]
    (is (= "org/repo#7" (get-in proposal [:changeRequest :displayId])))
    (is (= {:body "Updated body"} (:changeRequestUpdate proposal)))
    (is (= [["org/repo" "7" {:body "Updated body"}]] @calls))
    (is (= "Updated body" (get-in result [:changeRequestUpdate :body])))))

(deftest item-comments-load-only-tracker-credentials
  (let [built-roles (atom [])]
    (with-redefs [config/load-config (fn [_ _ roles]
                                       (is (= [:tracker] roles))
                                       {:forge {:provider :github}
                                        :tracker {:provider :linear}})
                  adapters/build (fn [_ role _]
                                   (swap! built-roles conj role)
                                   {:provider :linear})]
      (is (= #{:config :tracker}
             (set (keys (agent/request-runtime {} {:action "comment_item"})))))
      (is (= [:tracker] @built-roles)))))

(deftest standalone-item-actions-load-only-the-tracker
  (doseq [action ["create_item" "update_item"]]
    (let [built-roles (atom [])]
      (with-redefs [config/load-config (fn [_ _ roles]
                                         (is (= [:tracker] roles))
                                         {:forge {:provider :github}
                                          :tracker {:provider :linear}})
                    adapters/build (fn [_ role _]
                                     (swap! built-roles conj role)
                                     {:provider :linear})]
        (is (= #{:config :tracker}
               (set (keys (agent/request-runtime {} {:action action})))))
        (is (= [:tracker] @built-roles))))))

(deftest list-items-loads-only-tracker-configuration
  (let [built-roles (atom [])]
    (with-redefs [config/load-config (fn [_ _ roles]
                                       (is (= [:tracker] roles))
                                       {:tracker {:provider :linear}})
                  adapters/build (fn [_ role _]
                                   (swap! built-roles conj role)
                                   {:configured-scope (constantly scope)
                                    :list-items (constantly [])})]
      (is (= {:items []}
             (agent/execute-command "list" ["--kind" "item"])))
      (is (= [:tracker] @built-roles)))))

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
