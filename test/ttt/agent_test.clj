(ns ttt.agent-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.cli.agent :as agent]
            [ttt.domain :as domain]))

(def scope (domain/scope-identity :linear "team-1"))
(def item {:ref (domain/identity :linear :tracker-item "issue-1") :display-id "APP-123" :title "Retry" :description "Description" :url "https://linear/item" :scopes [scope] :labels []})
(def source {:branch "retry" :repository {:ref (domain/identity :github :repository "org/repo") :display-id "org/repo"} :change-request {:ref (domain/contained-identity :github :change-request "org/repo" 7) :display-id "org/repo#7" :title "Retry" :body "Body" :url "https://github/pr"}})
(def config {:change-request {:body-begin-marker "<!-- ttt:begin -->" :body-end-marker "<!-- ttt:end -->" :section-title "Tracker"}})
(defn runtime [calls]
  {:config config :forge {:inspect-current (fn [] source) :prefix-change-request-title (fn [id title] (str "[" id "] " title)) :update-change-request! (fn [& _] (swap! calls conj :forge))}
   :tracker {:configured-scope (fn [] scope) :resolve-item (fn [ref] (when (= ref "APP-123") item)) :resolve-parent-item (fn [_] nil) :resolve-project (fn [_] nil) :resolve-labels (fn [_ _] []) :search-parent-items (fn [] [item]) :search-projects (fn [] []) :search-labels (fn [] []) :update-item! (fn [resolved _] (swap! calls conj :tracker) resolved) :create-item! (fn [& _] item)}})

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

(deftest version-advertises-agent-api-v2
  (is (= 2 (:agentApiVersion (agent/execute-command "version" [])))))
(deftest preview-is-read-only-and-uses-neutral-wire-fields
  (let [calls (atom []) proposal (agent/preview-data (runtime calls) {:action "link_existing" :item "APP-123" :labels []})]
    (is (empty? @calls))
    (is (.startsWith (:proposalId proposal) "lp2_"))
    (is (contains? proposal :changeRequestUpdate))
    (is (not (contains? proposal :linearDescription)))
    (is (= "7" (get-in proposal [:source :changeRequest :identity :id])))))
(deftest mismatched-proposal-is-rejected-before-mutation
  (let [calls (atom [])]
    (is (thrown-with-msg? Exception #"Approval does not match" (agent/apply-data! (runtime calls) {:action "link_existing" :item "APP-123" :labels []} "lp2_wrong")))
    (is (empty? @calls))))
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
