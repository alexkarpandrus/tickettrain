(ns ttt.linear-test
  (:require
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.providers.tracker.linear :as linear]))

(def config
  {:tracker {:provider :linear
             :api-key "token"
             :team-id "team-1"
             :workspace-url "https://linear.app/acme"}
   :change-request {:body-begin-marker "<!-- ttt:begin -->"
                    :body-end-marker "<!-- ttt:end -->"}})

(deftest assignee-id-resolves-self-through-viewer
  (with-redefs [linear/viewer (fn [_] {:id "viewer-1"})]
    (is (= "viewer-1"
           (linear/assignee-id (assoc-in config [:tracker :assignee-id] "self"))))))

(deftest assignee-id-uses-explicit-value
  (is (= "user-123"
         (linear/assignee-id (assoc-in config [:tracker :assignee-id] "user-123")))))

(deftest state-id-prefers-explicit-id
  (is (= "state-123"
         (linear/state-id (assoc-in config [:tracker :state-id] "state-123") "team-1"))))

(deftest state-id-resolves-by-name
  (with-redefs [linear/team-states (fn [_ _]
                                     [{:id "state-1" :name "Todo"}
                                      {:id "state-2" :name "In Review"}])]
    (is (= "state-2"
           (linear/state-id (assoc-in config [:tracker :state-name] "In Review") "team-1")))))

(deftest state-id-fails-when-name-is-missing
  (with-redefs [linear/team-states (fn [_ _] [{:id "state-1" :name "Todo"}])]
    (is (thrown-with-msg?
         Exception
         #"Linear workflow state not found"
         (linear/state-id (assoc-in config [:tracker :state-name] "In Review") "team-1")))))

(deftest parent-issues-page-and-normalize-item-scope
  (let [calls (atom [])]
    (with-redefs [linear/graphql! (fn [_ _ variables]
                                    (swap! calls conj variables)
                                    (if (nil? (:after variables))
                                      {:team {:issues {:nodes [{:id "i1"
                                                               :identifier "APP-1"
                                                               :team {:id "team-1"}}]
                                                       :pageInfo {:hasNextPage true
                                                                  :endCursor "cursor-1"}}}}
                                      {:team {:issues {:nodes [{:id "i2"
                                                               :identifier "APP-2"
                                                               :team {:id "team-1"}}]
                                                       :pageInfo {:hasNextPage false
                                                                  :endCursor nil}}}}))]
      (let [items (linear/normalized-parent-items config)]
        (is (= ["APP-1" "APP-2"] (mapv :display-id items)))
        (is (every? #(domain/entity-in-scope?
                      %
                      (domain/scope-identity :linear "team-1"))
                    items))
        (is (= [nil "cursor-1"] (mapv :after @calls)))))))

(deftest paginate-stops-when-page-claims-more-without-a-cursor
  (with-redefs [linear/graphql! (fn [_ _ variables]
                                  (is (nil? (:after variables)))
                                  {:team {:issues {:nodes [{:id "i1" :identifier "APP-1" :team {:id "team-1"}}]
                                                   :pageInfo {:hasNextPage true :endCursor nil}}}})]
    (is (= ["APP-1"] (mapv :display-id (linear/normalized-parent-items config))))))

(deftest projects-page-and-normalize-scopes
  (with-redefs [linear/graphql! (fn [_ _ variables]
                                  (if (nil? (:after variables))
                                    {:projects {:nodes [{:id "p1"
                                                        :name "One"
                                                        :slugId "one"
                                                        :teams {:nodes [{:id "team-1"}]}}]
                                                :pageInfo {:hasNextPage true
                                                           :endCursor "cursor-1"}}}
                                    {:projects {:nodes [{:id "p2"
                                                        :name "Two"
                                                        :slugId "two"
                                                        :teams {:nodes [{:id "team-1"}]}}]
                                                :pageInfo {:hasNextPage false
                                                           :endCursor nil}}}))]
    (let [projects (linear/normalized-projects config)]
      (is (= ["one" "two"] (mapv :display-id projects)))
      (is (every? #(domain/entity-in-scope?
                    %
                    (domain/scope-identity :linear "team-1"))
                  projects)))))

(deftest item-by-identifier-uses-direct-query-and-normalizes
  (let [variables (atom nil)]
    (with-redefs [linear/graphql! (fn [_ query input]
                                    (is (= linear/issue-by-identifier-query query))
                                    (reset! variables input)
                                    {:issue {:id "issue-1"
                                             :identifier "APP-324"
                                             :title "Parent"
                                             :team {:id "team-1"}
                                             :labels {:nodes [{:id "bug"
                                                               :name "Bug"
                                                               :team {:id "team-1"}}]}}})]
      (let [item (linear/normalized-item-by-identifier config "APP-324")]
        (is (= "APP-324" (:display-id item)))
        (is (= (domain/identity :linear :tracker-item "issue-1") (:ref item)))
        (is (= [(domain/scope-identity :linear "team-1")]
               (get-in item [:labels 0 :scopes])))
        (is (= {:issueId "APP-324"} @variables))))))

(deftest normalized-project-by-ref-matches-normalized-slug-and-name
  (with-redefs [linear/normalized-projects (fn [_]
                                             [(linear/normalize-project
                                               {:id "project-1"
                                                :name "Tech Debt"
                                                :slugId "tech-debt"})])]
    (is (= "project-1" (get-in (linear/normalized-project-by-ref config "tech-debt") [:ref :id])))
    (is (= "project-1" (get-in (linear/normalized-project-by-ref config "Tech Debt") [:ref :id])))))


(deftest labels-page-and-normalize-provider-data
  (with-redefs [linear/graphql! (fn [_ _ variables]
                                  (if (nil? (:after variables))
                                    {:issueLabels {:nodes [{:id "group"
                                                           :name "Type"
                                                           :isGroup true}
                                                          {:id "bug"
                                                           :name "Bug"
                                                           :isGroup false
                                                           :team {:id "team-1"}}]
                                                   :pageInfo {:hasNextPage true
                                                              :endCursor "cursor-1"}}}
                                    {:issueLabels {:nodes [{:id "backend"
                                                           :name "Backend"
                                                           :isGroup false}]
                                                   :pageInfo {:hasNextPage false
                                                              :endCursor nil}}}))]
    (let [labels (linear/normalized-labels config)]
      (is (= ["bug" "backend"] (mapv #(get-in % [:ref :id]) labels)))
      (is (= "Bug" (:display-id (first labels)))))))

(deftest normalized-label-by-ref-resolves-an-existing-team-label
  (with-redefs [linear/normalized-labels (fn [_]
                                           [(linear/normalize-label
                                             {:id "bug" :name "Bug" :team {:id "team-1"}})
                                            (linear/normalize-label
                                             {:id "other" :name "Bug" :team {:id "team-2"}})])]
    (is (= "bug"
           (get-in (linear/normalized-label-by-ref
                    config
                    "Bug"
                    (domain/scope-identity :linear "team-1"))
                   [:ref :id])))))

(deftest create-item-from-intent-includes-context-and-normalizes-result
  (let [variables (atom nil)]
    (with-redefs [linear/assignee-id (fn [_] nil)
                  linear/state-id (fn [_ _] nil)
                  linear/graphql! (fn [_ query input]
                                    (is (= linear/create-issue-mutation query))
                                    (reset! variables input)
                                    {:issueCreate {:success true
                                                   :issue {:id "issue-1"
                                                           :identifier "APP-1"
                                                           :team {:id "team-1"}}}})]
      (let [item (linear/create-item-from-intent!
                  config
                  {:parent {:ref (domain/identity :linear :tracker-item "parent-1")}
                   :project {:ref (domain/identity :linear :project "project-1")}}
                  {:title "Title"
                   :description "Description"
                   :labels [{:ref (domain/identity :linear :label "bug")}
                            {:ref (domain/identity :linear :label "backend")}]})]
        (is (= {:teamId "team-1"
                :title "Title"
                :description "Description"
                :parentId "parent-1"
                :projectId "project-1"
                :labelIds ["bug" "backend"]}
               (get-in @variables [:input])))
        (is (= "APP-1" (:display-id item)))))))

(deftest update-item-from-intent-sends-additive-labels-and-normalizes-result
  (let [variables (atom nil)]
    (with-redefs [linear/graphql! (fn [_ query input]
                                    (is (= linear/update-issue-mutation query))
                                    (reset! variables input)
                                    {:issueUpdate {:success true
                                                   :issue {:id "issue-1"
                                                           :identifier "APP-1"
                                                           :team {:id "team-1"}}}})]
      (let [item (linear/update-item-from-intent!
                  config
                  {:ref (domain/identity :linear :tracker-item "issue-1")}
                  {:description "Updated"
                   :labels [{:ref (domain/identity :linear :label "existing")}
                            {:ref (domain/identity :linear :label "added")}]})]
        (is (= {:id "issue-1"
                :input {:description "Updated"
                        :labelIds ["existing" "added"]}}
               @variables))
        (is (= (domain/identity :linear :tracker-item "issue-1")
               (:ref item)))))))

(deftest configured-scope-and-neutral-adapter-capabilities-are-neutral
  (is (= (domain/scope-identity :linear "team-1")
         (linear/configured-scope config)))
  (let [adapter (linear/neutral-adapter config)]
    (is (= linear/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) linear/capabilities))))

(deftest adapter-maps-neutral-labels-at-the-provider-boundary
  (let [created (atom nil)
        updated (atom nil)
        adapter (linear/neutral-adapter config)
        context {:parent {:ref (domain/identity :linear :tracker-item "parent-1")
                          :scopes [(domain/scope-identity :linear "team-1")]}}
        item {:ref (domain/identity :linear :tracker-item "issue-1")}
        labels [{:ref (domain/identity :linear :label "bug")}
                {:ref (domain/identity :linear :label "backend")}]]
    (with-redefs [linear/create-item! (fn [& args]
                                        (reset! created args)
                                        {:id "issue-1"})
                  linear/update-item! (fn [& args]
                                        (reset! updated args)
                                        {:id "issue-1"})]
      ((:create-item! adapter)
       context
       {:title "Title" :description "Description" :labels labels})
      ((:update-item! adapter)
       item
       {:description "Updated" :labels labels}))
    (is (= [config {:parent {:id "parent-1" :team {:id "team-1"}}}
            "Title"
            "Description"
            ["bug" "backend"]]
           @created))
    (is (= [config "issue-1"
            {:description "Updated" :labelIds ["bug" "backend"]}]
           @updated))))

(deftest normalized-items-expose-state-parent-and-project
  (let [mock {:team {:issues {:nodes [{:id "i1"
                                       :identifier "APP-1"
                                       :title "Fix retry"
                                       :team {:id "team-1"}
                                       :state {:id "st-1" :name "In Progress" :type "started"}
                                       :project {:id "p1" :name "Platform" :slugId "platform" :url "https://linear/project/platform"}
                                       :parent {:id "epic-1" :identifier "EPIC-1" :title "Epic" :url "https://linear/issue/EPIC-1"}}]
                               :pageInfo {:hasNextPage false :endCursor nil}}}}]
    (with-redefs [linear/graphql! (fn [_ _ _] mock)]
      (let [item (first (linear/normalized-parent-items config))]
        (is (= "In Progress" (get-in item [:state :name])))
        (is (= "started" (get-in item [:state :type])))
        (is (= "https://linear/project/platform" (get-in item [:project :url])))
        (is (= "platform" (get-in item [:project :display-id])))
        (is (= "EPIC-1" (get-in item [:parent :display-id])))))))

(deftest issue-queries-select-workflow-state
  (doseq [query [linear/parent-issues-query
                 linear/issue-by-identifier-query
                 linear/create-issue-mutation
                 linear/update-issue-mutation]]
    (is (re-find #"state \{ id name type \}" query))))
