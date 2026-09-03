(ns ttt.provider-integration-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.core :as core]
            [ttt.forge :as forge]
            [ttt.shell :as shell]
            [ttt.tracker :as tracker]
            [ttt.tracker.linear :as linear]))

(def config {:tracker {:provider :linear :api-key "test-token" :team-id "team-1" :workspace-url "https://linear.app/acme"}
             :forge {:provider :github}
             :change-request {:body-begin-marker "<!-- ttt:begin -->" :body-end-marker "<!-- ttt:end -->" :section-title "Tracker"}})
(def item-response {:id "issue-1" :identifier "APP-123" :title "Retry failed requests" :description "Tracker description" :url "https://linear/item" :team {:id "team-1" :key "APP" :name "Application"} :labels {:nodes [{:id "existing" :name "Existing" :team {:id "team-1" :key "APP" :name "Application"}}]}})

(defn command-stub [order forge-payload]
  (fn [& args]
    (let [args (vec args)]
      (cond
        (= ["gh" "repo" "view"] (subvec args 0 3)) (json/generate-string {:nameWithOwner "org/repo" :defaultBranchRef {:name "main"}})
        (= ["gh" "pr" "view"] (subvec args 0 3)) (json/generate-string {:number 7 :title "Improve retry handling" :body "User body" :url "https://github.com/org/repo/pull/7" :headRefName "retry" :baseRefName "main"})
        (= ["git" "rev-parse"] (subvec args 0 2)) "retry"
        (= ["gh" "api"] (subvec args 0 2)) (do (swap! order conj :forge) (reset! forge-payload {:endpoint (nth args 2) :payload (json/parse-string (slurp (nth args (inc (.indexOf args "--input")))) true)}) "{}")
        :else (throw (ex-info "Unexpected command" {:args args}))))))
(defn graphql-stub [order tracker-payload]
  (fn [_ query variables]
    (cond
      (= query linear/issue-by-identifier-query) {:issue item-response}
      (= query linear/issue-labels-query) {:issueLabels {:nodes [{:id "bug" :name "Bug" :isGroup false :team {:id "team-1" :key "APP" :name "Application"}}] :pageInfo {:hasNextPage false :endCursor nil}}}
      (= query linear/update-issue-mutation) (do (swap! order conj :tracker) (reset! tracker-payload variables) {:issueUpdate {:success true :issue item-response}})
      (= query linear/create-issue-mutation) (do (swap! order conj :tracker-create) (reset! tracker-payload variables) {:issueCreate {:success true :issue (assoc item-response :id "issue-2" :identifier "APP-200" :labels {:nodes []})}})
      :else (throw (ex-info "Unexpected GraphQL operation" {:query query :variables variables})))))

(deftest registry-runtime-applies-link-intent-at-native-boundaries
  (let [runtime (adapters/runtime config forge/registry tracker/registry) order (atom []) tracker-payload (atom nil) forge-payload (atom nil)]
    (with-redefs [shell/run (command-stub order forge-payload) linear/graphql! (graphql-stub order tracker-payload)]
      (let [proposal (core/preview runtime {:action :link-existing :item-ref "APP-123" :labels ["Bug"]})]
        (is (= :github (get-in proposal [:source :repository :ref :provider])))
        (is (= :linear (get-in proposal [:item :ref :provider])))
        (is (empty? @order))
        (core/apply! runtime proposal)))
    (is (= [:tracker :forge] @order))
    (is (= {:id "issue-1" :input {:description (get-in @tracker-payload [:input :description]) :labelIds ["existing" "bug"]}} @tracker-payload))
    (is (= "repos/org/repo/pulls/7" (:endpoint @forge-payload)))))

(deftest registry-runtime-translates-create-context-before-forge-update
  (let [runtime (adapters/runtime config forge/registry tracker/registry) order (atom []) tracker-payload (atom nil) forge-payload (atom nil)]
    (with-redefs [shell/run (command-stub order forge-payload) linear/graphql! (graphql-stub order tracker-payload)]
      (let [parent (linear/normalize-item (assoc item-response :id "parent-1" :identifier "APP-100" :labels {:nodes []}))
            proposal (core/preview runtime {:action :create-new :context {:parent parent} :labels ["Bug"]})]
        (core/apply! runtime proposal)))
    (is (= [:tracker-create :forge] @order))
    (is (= "parent-1" (get-in @tracker-payload [:input :parentId])))
    (is (= ["bug"] (get-in @tracker-payload [:input :labelIds])))
    (is (= "[APP-200] Improve retry handling" (get-in @forge-payload [:payload :title])))))

(deftest adapter-build-rejects-invalid-registry-descriptors
  (let [config {:tracker {:provider :bad} :forge {:provider :github}}
        build-with (fn [descriptor] (adapters/build config :tracker {:bad {:build (constantly descriptor)}}))]
    (is (thrown-with-msg? Exception #"missing required capabilities"
                          (build-with {:provider :bad})))
    (is (thrown-with-msg? Exception #"does not match its registry key"
                          (build-with {:provider :github})))
    (is (thrown-with-msg? Exception #"Unsupported tracker provider"
                          (adapters/build config :tracker {})))))
