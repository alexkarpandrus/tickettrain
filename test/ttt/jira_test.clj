(ns ttt.jira-test
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is]]
            [ttt.cli.prompt :as prompt]
            [ttt.domain :as domain]
            [ttt.providers.tracker.jira :as jira]))

(def config {:tracker {:provider :jira
                       :email "alex@example.com"
                       :api-token "token"
                       :site-url "https://acme.atlassian.net"
                       :cloud-id "cloud-1"
                       :project "APP"}})

(def base "https://acme.atlassian.net")

(deftest adf-round-trips-plain-text
  (is (= "line one\nline two" (jira/adf->text (jira/text->adf "line one\nline two")))))

(deftest adf-renders-emphasis-without-visible-markers
  (let [text "_Change request body was empty._"
        adf (jira/text->adf text)]
    (is (= {:type "text"
            :text "Change request body was empty."
            :marks [{:type "em"}]}
           (get-in adf [:content 0 :content 0])))
    (is (= text (jira/adf->text adf)))))

(deftest adf-renders-generated-markdown-as-native-blocks
  (let [markdown (str "## What\n\n"
                      "Use **Basic auth** with `bb test`.\n\n"
                      "- [PR \\[KAN-1\\]](https://github.com/acme/repo/pull/15)\n"
                      "- No labels")
        normalized (str "## What\n\n"
                        "Use **Basic auth** with `bb test`.\n\n"
                        "- [PR KAN-1](https://github.com/acme/repo/pull/15)\n"
                        "- No labels")
        adf (jira/text->adf markdown)]
    (is (= ["heading" "paragraph" "paragraph" "paragraph" "bulletList"]
           (mapv :type (:content adf))))
    (is (= {:type "link"
            :attrs {:href "https://github.com/acme/repo/pull/15"}}
           (get-in adf [:content 4 :content 0 :content 0 :content 0 :marks 0])))
    (is (= normalized (jira/adf->text adf)))
    (is (= "[PR \\[KAN-1\\]](https://github.com/acme/repo/pull/15)"
           (jira/adf->text
            {:type "paragraph"
             :content [{:type "text"
                        :text "PR [KAN-1]"
                        :marks [{:type "link"
                                 :attrs {:href "https://github.com/acme/repo/pull/15"}}]}]})))))

(deftest description-reads-plain-string-and-adf
  (is (= "plain" (jira/description->text "plain")))
  (is (= "hello" (jira/description->text {:type "doc" :content [{:type "paragraph" :content [{:type "text" :text "hello"}]}]}))))

(deftest normalizes-issue-with-scope-project-parent-and-labels
  (let [issue {:key "APP-123"
               :fields {:summary "Retry"
                        :description "Body"
                        :status {:name "In Progress"}
                        :project {:id "p1" :key "APP" :name "App"}
                        :parent {:key "APP-1" :fields {:summary "Parent"}}
                        :labels ["backend"]}}
        item (jira/normalize-item base issue)]
    (is (= "APP-123" (:display-id item)))
    (is (= "Retry" (:title item)))
    (is (= "Body" (:description item)))
    (is (= "In Progress" (get-in item [:state :name])))
    (is (= "APP" (get-in item [:project :display-id])))
    (is (= "APP-1" (get-in item [:parent :display-id])))
    (is (= ["backend"] (mapv :display-id (:labels item))))
    (is (domain/entity-in-scope? item (domain/scope-identity :jira base)))))

(deftest resolve-item-uses-key-directly
  (with-redefs [jira/api! (fn [_ method path _]
                            (is (= :get method))
                            (is (= "/issue/APP-123" path))
                            {:key "APP-123" :fields {:summary "S"}})]
    (is (= "APP-123" (:display-id (jira/resolve-item config "APP-123"))))))

(deftest label-names-extract-from-normalized-labels
  (is (= ["backend" "bug"]
         (jira/label-names [(jira/normalize-label base "backend")
                            (jira/normalize-label base "bug")]))))

(deftest label-page-normalizes-string-values
  (with-redefs [jira/api! (fn [& _] {:values ["performance" "security"]})]
    (is (= ["performance" "security"]
           (mapv :display-id (jira/labels config))))))

(deftest scoped-api-token-uses-cloud-gateway-and-basic-authentication
  (let [call (atom nil)]
    (with-redefs [http/get (fn [url opts]
                             (reset! call [url (get-in opts [:headers "Authorization"])])
                             {:status 200 :body "{}"})]
      (jira/api! config :get "/myself" nil))
    (is (= "https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/myself" (first @call)))
    (is (= "alex@example.com:token"
           (String. (.decode (java.util.Base64/getDecoder) (subs (second @call) 6)) "UTF-8")))))


(deftest api-delete-uses-the-issue-endpoint
  (let [request (atom nil)]
    (with-redefs [http/delete (fn [url opts]
                                (reset! request [url (:headers opts)])
                                {:status 204 :body ""})]
      (jira/api! config :delete "/issue/APP-1" nil))
    (is (= "https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/issue/APP-1"
           (first @request)))
    (is (contains? (second @request) "Authorization"))))

(deftest issue-search-uses-the-supported-jql-endpoint
  (let [path-used (atom nil)]
    (with-redefs [jira/api! (fn [_ _ path _]
                              (reset! path-used path)
                              {:issues []})]
      (jira/search-issues config "project = APP" 5))
    (is (= "/search/jql" @path-used))))

(deftest project-statuses-are-filtered-to-the-configured-issue-type
  (with-redefs [jira/api! (fn [_ _ _ _]
                            [{:id "10001" :name "Task"
                              :statuses [{:id "1" :name "Todo"}
                                         {:id "2" :name "In Progress"}
                                         {:id "2" :name "In Progress"}]}
                             {:id "10002" :name "Bug"
                              :statuses [{:id "3" :name "Bug Review"}]}])]
    (is (= ["Todo" "In Progress"]
           (mapv :name (jira/project-statuses config "APP" {:name "Task"}))))))


(deftest invalid-status-for-the-issue-type-fails-before-create
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "Bug Review")]
    (with-redefs [jira/api! (fn [_ method _ _]
                              (swap! calls conj method)
                              [{:name "Task" :statuses [{:id "1" :name "Todo"}]}
                               {:name "Bug" :statuses [{:id "2" :name "Bug Review"}]}])]
      (is (thrown-with-msg?
           Exception
           #"Jira target state not found"
           (jira/create-item-from-intent!
            app-config {} {:title "Task" :description "Body" :labels []}))))
    (is (not-any? #{:post} @calls))))


(deftest missing-project-fails-before-create
  (let [calls (atom [])
        app-config (update config :tracker dissoc :project)
        error (with-redefs [jira/api! (fn [& args] (swap! calls conj args))]
                (try
                  (jira/create-item-from-intent!
                   app-config {} {:title "Task" :description "Body" :labels []})
                  nil
                  (catch Exception ex ex)))]
    (is (= :project-required (:code (ex-data error))))
    (is (empty? @calls))))

(deftest created-issue-transitions-to-the-configured-target-state
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "In Progress")]
    (with-redefs [jira/api! (fn [_ method path body]
                              (swap! calls conj [method path body])
                              (cond
                                (= [:get "/issue/APP-1/transitions"] [method path])
                                {:transitions [{:id "21" :to {:name "In Progress"} :fields {}}]}

                                (= [:get "/issue/APP-1"] [method path])
                                {:key "APP-1" :fields {:status {:name "In Progress"}}}))]
      (is (= "In Progress"
             (get-in (jira/apply-target-state!
                      app-config
                      {:key "APP-1" :fields {:status {:name "Todo"}}})
                     [:fields :status :name]))))
    (is (some #(= [:post "/issue/APP-1/transitions" {:transition {:id "21"}}] %)
              @calls))))


(deftest failed-target-state-deletes-the-created-issue
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "In Progress")
        error (with-redefs [jira/api!
                            (fn [_ method path body]
                              (swap! calls conj [method path body])
                              (case [method path]
                                [:get "/project/APP/statuses"]
                                [{:name "Task" :statuses [{:id "1" :name "Todo"}
                                                           {:id "2" :name "In Progress"}]}]
                                [:post "/issue"] {:key "APP-1"}
                                [:get "/issue/APP-1"] {:key "APP-1" :fields {:status {:name "Todo"}}}
                                [:get "/issue/APP-1/transitions"] {:transitions []}
                                [:delete "/issue/APP-1"] nil))]
                (try
                  (jira/create-item-from-intent!
                   app-config {} {:title "Task" :description "Body" :labels []})
                  nil
                  (catch Exception ex ex)))]
    (is (= :succeeded (:rollback (ex-data error))))
    (is (re-find #"APP-1 was deleted" (.getMessage error)))
    (is (some #(= [:delete "/issue/APP-1" nil] %) @calls))))

(deftest failed-target-state-reports-a-failed-rollback
  (let [app-config (assoc-in config [:tracker :target-state] "In Progress")
        error (with-redefs [jira/api!
                            (fn [_ method path _]
                              (case [method path]
                                [:get "/project/APP/statuses"]
                                [{:name "Task" :statuses [{:id "1" :name "Todo"}
                                                           {:id "2" :name "In Progress"}]}]
                                [:post "/issue"] {:key "APP-1"}
                                [:get "/issue/APP-1"] {:key "APP-1" :fields {:status {:name "Todo"}}}
                                [:get "/issue/APP-1/transitions"] {:transitions []}
                                [:delete "/issue/APP-1"] (throw (ex-info "Delete denied." {:status 403}))))]
                (try
                  (jira/create-item-from-intent!
                   app-config {} {:title "Task" :description "Body" :labels []})
                  nil
                  (catch Exception ex ex)))]
    (is (= :failed (:rollback (ex-data error))))
    (is (= "APP-1" (:created-item (ex-data error))))
    (is (re-find #"Inspect the issue before retrying" (.getMessage error)))))

(deftest refresh-failure-preserves-the-transitioned-issue
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "In Progress")
        error (with-redefs [jira/api!
                            (fn [_ method path _]
                              (swap! calls conj [method path])
                              (case [method path]
                                [:get "/issue/APP-1/transitions"]
                                {:transitions [{:id "21" :to {:name "In Progress"} :fields {}}]}
                                [:post "/issue/APP-1/transitions"] nil
                                [:get "/issue/APP-1"] (throw (ex-info "Refresh failed." {}))
                                [:delete "/issue/APP-1"] nil))]
                (try
                  (jira/apply-created-target-state!
                   app-config {:key "APP-1" :fields {:status {:name "Todo"}}})
                  nil
                  (catch Exception ex ex)))]
    (is (:transition-applied (ex-data error)))
    (is (re-find #"Inspect the issue before retrying" (.getMessage error)))
    (is (not-any? #(= [:delete "/issue/APP-1"] %) @calls))))


(deftest lost-transition-response-confirms-state-before-rollback
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "In Progress")
        result (with-redefs [jira/api!
                             (fn [_ method path _]
                               (swap! calls conj [method path])
                               (case [method path]
                                 [:get "/issue/APP-1/transitions"]
                                 {:transitions [{:id "21" :to {:name "In Progress"} :fields {}}]}
                                 [:post "/issue/APP-1/transitions"]
                                 (throw (ex-info "Response lost." {}))
                                 [:get "/issue/APP-1"]
                                 {:key "APP-1" :fields {:status {:name "In Progress"}}}
                                 [:delete "/issue/APP-1"] nil))]
                 (jira/apply-created-target-state!
                  app-config {:key "APP-1" :fields {:status {:name "Todo"}}}))]
    (is (= "In Progress" (get-in result [:fields :status :name])))
    (is (not-any? #(= [:delete "/issue/APP-1"] %) @calls))))

(deftest unknown-transition-outcome-preserves-the-created-issue
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "In Progress")
        error (with-redefs [jira/api!
                            (fn [_ method path _]
                              (swap! calls conj [method path])
                              (case [method path]
                                [:get "/issue/APP-1/transitions"]
                                {:transitions [{:id "21" :to {:name "In Progress"} :fields {}}]}
                                [:post "/issue/APP-1/transitions"]
                                (throw (ex-info "Response lost." {}))
                                [:get "/issue/APP-1"]
                                (throw (ex-info "Confirmation failed." {}))
                                [:delete "/issue/APP-1"] nil))]
                (try
                  (jira/apply-created-target-state!
                   app-config {:key "APP-1" :fields {:status {:name "Todo"}}})
                  nil
                  (catch Exception ex ex)))]
    (is (:preserve-created-item (ex-data error)))
    (is (re-find #"Inspect the issue before retrying" (.getMessage error)))
    (is (not-any? #(= [:delete "/issue/APP-1"] %) @calls))))

(deftest setup-selects-a-project-target-state
  (with-redefs [jira/api! (fn [_ _ path _]
                            (case path
                              "/myself" {:displayName "Alex"}
                              "/project/APP/statuses" [{:name "Task" :statuses [{:id "1" :name "Todo"}]}]))
                prompt/choose-index (fn [_ _] 1)]
    (is (= "Todo"
           (get-in (jira/setup config) [:tracker :target-state])))))

(deftest parent-create-uses-the-projects-subtask-issue-type
  (let [created-fields (atom nil)
        parent {:ref (domain/identity :jira :tracker-item "KAN-3")
                :project {:ref (domain/identity :jira :project "KAN")}}]
    (with-redefs [jira/api! (fn [_ method path body]
                              (cond
                                (= [:get "/project/KAN"] [method path])
                                {:issueTypes [{:id "10003" :name "Task" :subtask false}
                                              {:id "10002" :name "Subtask" :subtask true}]}

                                (= [:post "/issue"] [method path])
                                (do (reset! created-fields (:fields body)) {:key "KAN-4"})

                                (= [:get "/issue/KAN-4"] [method path])
                                {:key "KAN-4"
                                 :fields {:summary "Child"
                                          :project {:key "KAN" :name "Project"}
                                          :parent {:key "KAN-3" :fields {:summary "Parent"}}
                                          :labels []}}))]
      (is (= "KAN-4"
             (:display-id
              (jira/create-item-from-intent!
               config
               {:parent parent}
               {:title "Child" :description "Body" :labels []}))))
      (is (= {:parent {:key "KAN-3"}
              :project {:key "KAN"}
              :issuetype {:id "10002"}}
             (select-keys @created-fields [:parent :project :issuetype]))))))

(deftest neutral-adapter-declares-every-tracker-capability
  (let [adapter (jira/neutral-adapter config)]
    (is (= :jira (:provider adapter)))
    (is (= jira/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) jira/capabilities))))
