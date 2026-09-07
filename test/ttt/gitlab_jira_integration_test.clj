(ns ttt.gitlab-jira-integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.core :as core]
            [ttt.providers.forge :as forge]
            [ttt.platform.shell :as shell]
            [ttt.providers.forge.gitlab :as gitlab]
            [ttt.providers.tracker :as tracker]
            [ttt.providers.tracker.jira :as jira]))

(def config
  {:tracker {:provider :jira
             :email "alex@example.com"
             :api-token "token"
             :site-url "https://acme.atlassian.net"
             :cloud-id "cloud-1"
             :project "APP"}
   :forge {:provider :gitlab :token "tok" :base-url "https://gitlab.com"}
   :change-request {:body-begin-marker "<!-- ttt:begin -->"
                    :body-end-marker "<!-- ttt:end -->"
                    :section-title "Jira"}})

(def jira-issue
  {:key "APP-123"
   :fields {:summary "Retry"
            :description "Tracker description"
            :status {:name "Todo"}
            :labels []}})

(defn shell-stub
  [& args]
  (let [args (vec args)]
    (cond
      (= ["git" "rev-parse" "--abbrev-ref" "HEAD"] args) "retry"
      (= ["git" "remote" "get-url" "origin"] args) "git@gitlab.com:group/proj.git"
      :else (throw (ex-info "Unexpected shell call" {:args args})))))

(defn gitlab-stub
  [order payload]
  (fn [_ method path query]
    (cond
      (and (= :get method) (= "/projects/group%2Fproj" path))
      {:path_with_namespace "group/proj" :default_branch "main"}

      (and (= :get method) (= "/projects/group%2Fproj/merge_requests" path))
      [{:iid 7 :title "Retry" :description "User body"
        :web_url "https://gitlab.com/group/proj/-/merge_requests/7"
        :source_branch "retry" :target_branch "main"}]

      (and (= :put method) (= "/projects/group%2Fproj/merge_requests/7" path))
      (do (swap! order conj :forge) (reset! payload query) {})

      :else (throw (ex-info "Unexpected GitLab call" {:method method :path path})))))

(defn jira-stub
  [order payload]
  (fn [_ method path query]
    (cond
      (and (= :get method) (= "/issue/APP-123" path)) jira-issue

      (and (= :put method) (= "/issue/APP-123" path))
      (do (swap! order conj :tracker) (reset! payload query) {})

      (and (= :get method) (= "/issue/APP-200" path))
      (assoc jira-issue :key "APP-200")

      (and (= :get method) (= "/project/APP" path))
      {:issueTypes [{:id "10002" :name "Subtask" :subtask true}]}

      (and (= :post method) (= "/issue" path))
      (do (swap! order conj :tracker-create) (reset! payload query) {:key "APP-200"})

      (and (= :get method) (= "/label" path)) {:values []}

      :else (throw (ex-info "Unexpected Jira call" {:method method :path path})))))

(deftest gitlab-jira-applies-link-intent-at-native-boundaries
  (let [runtime (adapters/runtime config forge/registry tracker/registry)
        order (atom [])
        jira-payload (atom nil)
        gitlab-payload (atom nil)]
    (with-redefs [shell/run shell-stub
                  jira/api! (jira-stub order jira-payload)
                  gitlab/api! (gitlab-stub order gitlab-payload)]
      (let [proposal (core/preview runtime {:action :link-existing :item-ref "APP-123" :labels ["Bug"]})]
        (is (= :gitlab (get-in proposal [:source :repository :ref :provider])))
        (is (= :jira (get-in proposal [:item :ref :provider])))
        (is (= "group/proj!7" (get-in proposal [:source :change-request :display-id])))
        (is (empty? @order))
        (core/apply! runtime proposal)))
    (is (= [:tracker :forge] @order))
    (is (= ["Bug"] (get-in @jira-payload [:fields :labels])))
    (is (str/includes? (jira/adf->text (get-in @jira-payload [:fields :description]))
                       "Tracker description"))
    (is (= "[APP-123] Retry" (:title @gitlab-payload)))
    (is (str/includes? (:description @gitlab-payload) "## Jira"))))

(deftest gitlab-jira-translates-create-context-before-forge-update
  (let [runtime (adapters/runtime config forge/registry tracker/registry)
        order (atom [])
        jira-payload (atom nil)
        gitlab-payload (atom nil)]
    (with-redefs [shell/run shell-stub
                  jira/api! (jira-stub order jira-payload)
                  gitlab/api! (gitlab-stub order gitlab-payload)]
      (let [parent (jira/normalize-item "https://acme.atlassian.net"
                                        {:key "APP-1" :fields {:summary "Parent"}})
            proposal (core/preview runtime {:action :create-new :context {:parent parent} :labels ["Bug"]})]
        (core/apply! runtime proposal)))
    (is (= [:tracker-create :forge] @order))
    (is (= {:key "APP-1"} (get-in @jira-payload [:fields :parent])))
    (is (= {:id "10002"} (get-in @jira-payload [:fields :issuetype])))
    (is (= "[APP-200] Retry" (:title @gitlab-payload)))))
