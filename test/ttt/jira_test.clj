(ns ttt.jira-test
  (:require [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.providers.tracker :as tracker]
            [ttt.cli.prompt :as prompt]
            [ttt.domain :as domain]
            [ttt.providers.tracker.jira :as jira]
            [ttt.text.links :as links]))

(def config {:tracker {:provider :jira
                       :email "alex@example.com"
                       :api-token "token"
                       :site-url "https://acme.atlassian.net"
                       :cloud-id "cloud-1"
                       :project "APP"}})

(def base "https://acme.atlassian.net")


(deftest maps-jira-status-categories-to-neutral-states
  (doseq [[status expected] [[{:statusCategory {:key "new"}} "open"]
                             [{:statusCategory {:key "indeterminate"}} "active"]
                             [{:name "Done" :statusCategory {:key "done"}} "completed"]
                             [{:name "Canceled" :statusCategory {:key "done"}} "canceled"]]]
    (is (= expected (jira/normalize-state status)))))

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
                        :status {:name "In Progress" :statusCategory {:key "indeterminate"}}
                        :priority {:id "1" :name "Highest"}
                        :duedate "2026-09-30"
                        :issuelinks [{:id "link-1"
                                      :type {:name "Blocks" :outward "blocks"}
                                      :outwardIssue {:key "APP-100"
                                                     :fields {:summary "Deploy"}}}]
                        :project {:id "p1" :key "APP" :name "App"}
                        :parent {:key "APP-1" :fields {:summary "Parent"}}
                        :labels ["backend"]}}
        item (jira/normalize-item base issue)]
    (is (= "APP-123" (:display-id item)))
    (is (= "Retry" (:title item)))
    (is (= "Body" (:description item)))
    (is (= "active" (:state item)))
    (is (= "urgent" (:priority item)))
    (is (= "2026-09-30T00:00:00Z" (:due-at item)))
    (is (= ["APP-100"] (mapv :display-id (:blocked-by item))))
    (is (= "APP" (get-in item [:project :display-id])))
    (is (= "APP-1" (get-in item [:parent :display-id])))
    (is (= ["backend"] (mapv :display-id (:labels item))))
    (is (domain/entity-in-scope? item (domain/scope-identity :jira base)))))


(deftest update-preserves-rich-adf-outside-the-managed-section
  (let [mention {:type "paragraph"
                 :content [{:type "mention" :attrs {:id "account-1" :text "Alex"}}]}
        card {:type "inlineCard" :attrs {:url "https://example.com/card"}}
        original {:type "doc"
                  :version 1
                  :content [mention
                            {:type "heading"
                             :attrs {:level 2}
                             :content [{:type "text" :text "Pull requests"}]}
                            {:type "paragraph" :content []}
                            {:type "bulletList"
                             :content [{:type "listItem"
                                        :content [{:type "paragraph"
                                                   :content [{:type "text" :text "old" :marks [{:type "link" :attrs {:href "https://example.com/old"}}]}]}]}]}
                            card]}
        issue {:key "APP-123"
               :fields {:summary "Retry" :description original :labels []}}
        item (jira/normalize-item base issue)
        sent (atom nil)
        description (links/upsert-change-request
                     (jira/description->text original)
                     {:display-id "acme/repo#1"
                      :title "Retry"
                      :url "https://github.com/acme/repo/pull/1"})]
    (with-redefs [jira/api! (fn [_ method _ body]
                              (case method
                                :put (reset! sent body)
                                :get issue))]
      (jira/update-item-from-intent! config item {:description description :labels []}))
    (let [content (get-in @sent [:fields :description :content])]
      (is (= mention (first content)))
      (is (= card (last content)))
      (is (some #(str/includes? (jira/adf->text %) "acme/repo#1") content)))))

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

(deftest translates-neutral-priority-and-due-date
  (with-redefs [jira/api! (fn [_ method path _]
                            (is (= [:get "/priority"] [method path]))
                            [{:id "1" :name "Highest"}
                             {:id "2" :name "High"}
                             {:id "3" :name "Medium"}
                             {:id "4" :name "Low"}])]
    (is (= {:priority {:id "2"} :duedate "2026-09-30"}
           (jira/native-work-item-input
            config
            {:priority "high" :due-at "2026-09-30T12:00:00Z"})))
    (is (= {:priority nil}
           (jira/native-work-item-input config {:priority "none"})))))

(deftest blocker-link-type-validation-fails-during-preview
  (with-redefs [jira/api! (fn [_ method path _]
                            (is (= [:get "/issueLinkType"] [method path]))
                            {:issueLinkTypes []})]
    (let [error (try
                  (jira/validate-work-item-intent!
                   config :update-item {:display-id "APP-1"} {:blocked-by []})
                  nil
                  (catch Exception ex ex))]
      (is (= :unsupported-work-item-value (:code (ex-data error))))
      (is (= :blocked-by (:field (ex-data error)))))))

(deftest create-validation-uses-the-request-project-context
  (let [calls (atom [])]
    (with-redefs [jira/api! (fn [_ method path _]
                             (swap! calls conj [method path])
                             (case path
                               "/project/KAN/statuses"
                               [{:name "Task" :statuses [{:name "In Progress"
                                                        :statusCategory {:key "indeterminate"}}]}]
                               "/issue/createmeta/KAN/issuetypes"
                               {:startAt 0 :total 1 :issueTypes [{:id "10001" :name "Task"}]}
                               "/issue/createmeta/KAN/issuetypes/10001"
                               {:startAt 0 :total 0 :fields []}))]
      (jira/validate-work-item-intent!
       (update config :tracker dissoc :project)
       :create-item
       {:project {:ref (domain/identity :jira :project "KAN")}}
       {:state "active"}))
    (is (= [[:get "/project/KAN/statuses"]
            [:get "/issue/createmeta/KAN/issuetypes"]
            [:get "/issue/createmeta/KAN/issuetypes/10001"]] @calls))))

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


(deftest list-items-follows-page-tokens-until-the-filtered-limit-is-met
  (let [calls (atom [])]
    (with-redefs [jira/api! (fn [_ _ path params]
                              (is (= "/search/jql" path))
                              (swap! calls conj params)
                              (if (:nextPageToken params)
                                {:issues [{:key "APP-2"
                                           :fields {:summary "Open"
                                                    :status {:statusCategory {:key "new"}}}}]}
                                {:issues [{:key "APP-1"
                                           :fields {:summary "Done"
                                                    :status {:statusCategory {:key "done"}}}}]
                                 :nextPageToken "page-2"}))]
      (is (= ["APP-2"]
             (mapv :display-id (jira/list-items config #(= "open" (:state %)) 1))))
      (is (= [nil "page-2"] (mapv :nextPageToken @calls)))
      (is (= [100 100] (mapv :maxResults @calls))))))

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

(deftest nil-issue-type-uses-the-jira-default
  (is (= {:name "Task"}
         (jira/configured-issue-type (assoc-in config [:tracker :issue-type] nil)))))


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

(deftest neutral-state-selects-a-matching-jira-transition
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :target-state] "completed")]
    (with-redefs [jira/api! (fn [_ method path body]
                              (swap! calls conj [method path body])
                              (case [method path]
                                [:get "/issue/APP-1/transitions"]
                                {:transitions [{:id "21"
                                                :to {:name "Done"
                                                     :statusCategory {:key "done"}}
                                                :fields {}}]}
                                [:post "/issue/APP-1/transitions"] nil
                                [:get "/issue/APP-1"]
                                {:key "APP-1"
                                 :fields {:status {:name "Done"
                                                  :statusCategory {:key "done"}}}}))]
      (is (= "Done"
             (get-in (jira/apply-target-state!
                      app-config
                      {:key "APP-1"
                       :fields {:status {:name "Todo"
                                        :statusCategory {:key "new"}}}})
                     [:fields :status :name]))))
    (is (some #(= [:post "/issue/APP-1/transitions" {:transition {:id "21"}}] %)
              @calls))))

(deftest reconciles-jira-blocking-links-with-a-custom-link-type
  (let [calls (atom [])
        app-config (assoc-in config [:tracker :blocker-link-type] "Dependency")
        item {:ref (domain/identity :jira :tracker-item "APP-1")
              :provider-blocker-links
              [{:id "link-1"
                :item {:ref (domain/identity :jira :tracker-item "APP-2")}}]}
        blockers [{:ref (domain/identity :jira :tracker-item "APP-3")}]]
    (with-redefs [jira/api! (fn [_ method path body]
                              (swap! calls conj [method path body])
                              (when (= [:get "/issueLinkType"] [method path])
                                {:issueLinkTypes [{:id "10042" :name "Dependency"}]}))]
      (jira/sync-blockers! app-config item blockers))
    (is (= [[:get "/issueLinkType" nil]
            [:post "/issueLink"
             {:type {:id "10042"}
              :outwardIssue {:key "APP-3"}
              :inwardIssue {:key "APP-1"}}]
            [:delete "/issueLink/link-1" nil]]
           @calls))))

(deftest normalizes-custom-jira-blocker-links
  (let [item (jira/normalize-item
              base "10042"
              {:key "APP-1"
               :fields {:summary "Task"
                        :issuelinks [{:id "link-1"
                                      :type {:id "10042" :name "Dependency"}
                                      :outwardIssue {:key "APP-2"
                                                     :fields {:summary "Blocker"}}}]}})]
    (is (= ["APP-2"] (mapv :display-id (:blocked-by item))))))

(deftest reports-created-issue-when-blocker-sync-fails
  (let [error (with-redefs [jira/create-item! (fn [& _]
                                                {:key "APP-1"
                                                 :fields {:summary "Task"}})
                            jira/sync-blockers! (fn [& _]
                                                  (throw (ex-info "denied" {:status 403})))]
                (try
                  (jira/create-item-from-intent!
                   config {} {:title "Task" :description "" :labels [] :blocked-by []})
                  nil
                  (catch Exception ex ex)))]
    (is (= "APP-1" (:created-item (ex-data error))))
    (is (:preserve-created-item (ex-data error)))))


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
                prompt/choose-index (fn [& _] 1)]
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


(deftest comments-on-issues-use-adf
  (let [request (atom nil)]
    (with-redefs [jira/api! (fn [& args] (reset! request args))]
      (jira/comment-item! config {:ref (domain/identity :jira :tracker-item "KAN-3")} "Looks **good**"))
    (is (= config (first @request)))
    (is (= :post (second @request)))
    (is (= "/issue/KAN-3/comment" (nth @request 2)))
    (is (= "Looks **good**" (jira/adf->text (:body (nth @request 3)))))))

(deftest neutral-adapter-declares-every-tracker-capability
  (let [adapter (jira/neutral-adapter config)]
    (is (= :jira (:provider adapter)))
    (is (= jira/capabilities (:capabilities adapter)))
    (is (= #{:item-lifecycle :item-priority :item-due-dates :item-blockers :item-custom-fields}
           (:item-capabilities adapter)))
    (is (every? #(fn? (get adapter %)) jira/capabilities))))

(defn creation-runtime []
  {:config config :tracker (adapters/build config :tracker tracker/registry)})


(defn create-metadata-stub
  [path query fields]
  (case path
    "/issue/createmeta/APP/issuetypes"
    {:startAt 0 :total 1 :issueTypes [{:id "10001" :name "Task"}]}
    "/issue/createmeta/APP/issuetypes/10001"
    {:startAt 0 :total (count fields) :fields fields}
    (throw (ex-info "Unexpected create metadata request" {:path path :query query}))))

(deftest custom-fields-are-approved-and-sent-in-the-initial-create
  (let [writes (atom [])
        fields {:customfield_10001 [{:id "10010"} {:value "Other"}]
                :customfield_10002 {:nested [false 42 nil "Text"]}}
        request {:action "create_item" :title "Bug" :description "Body"
                 :customFields fields}
        runtime (creation-runtime)]
    (with-redefs [jira/api! (fn [_ method path body]
                             (case [method path]
                               [:get "/issue/createmeta/APP/issuetypes"] (create-metadata-stub path body [])
                               [:get "/issue/createmeta/APP/issuetypes/10001"] (create-metadata-stub path body [])
                               [:post "/issue"] (do (swap! writes conj body) {:key "APP-200"})
                               [:get "/issue/APP-200"]
                               {:key "APP-200" :fields {:summary "Bug" :description "Body"}}))]
      (let [preview (agent/preview-data runtime request)]
        (is (= fields (get-in preview [:request :customFields])))
        (is (= fields (get-in preview [:trackerIntent :customFields])))
        (is (empty? @writes))
        (is (thrown-with-msg? Exception #"Approval does not match"
                              (agent/apply-data! runtime
                                                 (assoc-in request [:customFields :customfield_10001 0 :id] "10011")
                                                 (:proposalId preview))))
        (is (empty? @writes))
        (is (= "APP-200" (get-in (agent/apply-data! runtime request (:proposalId preview))
                                [:item :displayId])))
        (is (= fields (select-keys (get-in @writes [0 :fields]) (keys fields))))
        (is (= {:summary "Bug" :project {:key "APP"} :issuetype {:id "10001"}}
               (select-keys (get-in @writes [0 :fields]) [:summary :project :issuetype])))
        (is (= fields (get-in (json/parse-string (json/generate-string preview) true)
                             [:trackerIntent :customFields])))))))

(deftest custom-fields-cannot-override-built-in-fields
  (doseq [field [:project :summary :priority :customfield_name :other/customfield_1]]
    (is (thrown-with-msg? Exception #"customfield_<digits>"
                          (agent/preview-data (creation-runtime)
                                              {:action "create_item" :title "Bug"
                                               :customFields {field "Override"}})))))

(deftest rejected-jira-create-does-not-claim-a-created-item
  (let [writes (atom [])
        runtime (creation-runtime)
        request {:action "create_item" :title "Bug" :comment "Testing"
                 :customFields {:customfield_10001 [{:id "10010"}]}}]
    (with-redefs [jira/api! (fn [_ method path query]
                             (if (= method :get)
                               (create-metadata-stub path query [])
                               (do
                                 (swap! writes conj [method path])
                                 (throw (ex-info "Jira API request failed with status 400. Field Exchanges must have value"
                                                 {:provider :jira :status 400
                                                  :detail "Field Exchanges must have value"})))))
                  agent/request-runtime (fn [_ _] runtime)]
      (let [preview (agent/preview-data runtime request)
            {:keys [exit envelope]} (agent/run ["apply" "--request" (json/generate-string request)
                                               "--approve" (:proposalId preview)])]
        (is (= 2 exit))
        (is (= "remote-api-error" (get-in envelope [:error :code])))
        (is (= 400 (get-in envelope [:error :status])))
        (is (= "Field Exchanges must have value" (get-in envelope [:error :details])))
        (is (not (contains? (:error envelope) :partialResult)))
        (is (not (contains? envelope :data)))
        (is (= [[:post "/issue"]] @writes))))))


(deftest preview-reports-discoverable-missing-required-fields
  (let [metadata [{:fieldId "customfield_10001" :name "Exchanges" :required true :hasDefaultValue false}]
        runtime (creation-runtime)]
    (with-redefs [jira/api! (fn [_ method path query]
                             (is (= :get method))
                             (create-metadata-stub path query metadata))]
      (doseq [request [{:action "create_item" :title "Bug"}
                       {:action "create_item" :title "Bug" :customFields {:customfield_10001 nil}}
                       {:action "create_item" :title "Bug" :customFields {:customfield_10001 []}}]]
        (is (thrown-with-msg? Exception #"Exchanges \(customfield_10001\)"
                              (agent/preview-data runtime request))))
      (let [preview (agent/preview-data runtime
                                       {:action "create_item" :title "Bug"
                                        :customFields {:customfield_10001 [{:id "10010"}]}})]
        (is (= "10001" (get-in preview [:trackerIntent :validation :issueType :id])))
        (is (= (mapv #(dissoc % :required) metadata)
               (get-in preview [:trackerIntent :validation :requiredFields])))
        (is (re-find #"does not check every workflow validator"
                     (get-in preview [:trackerIntent :validation :limitation])))))))

(deftest create-validation-honors-defaults-and-native-false-or-zero
  (let [required {:fieldId "customfield_10001" :name "Value" :required true :hasDefaultValue true}
        runtime (creation-runtime)]
    (with-redefs [jira/api! (fn [_ _ path query] (create-metadata-stub path query [required]))]
      (is (agent/preview-data runtime {:action "create_item" :title "Bug"}))
      (doseq [value [false 0]]
        (is (agent/preview-data runtime {:action "create_item" :title "Bug"
                                         :customFields {:customfield_10001 value}})))
      (doseq [value [nil "" " " [] {}]]
        (is (thrown-with-msg? Exception #"Jira creation requires fields"
                              (agent/preview-data runtime {:action "create_item" :title "Bug"
                                                           :customFields {:customfield_10001 value}})))))))

(deftest required-description-and-labels-use-the-complete-creation-intent
  (with-redefs [jira/api! (fn [_ _ path query]
                           (create-metadata-stub path query
                                                 [{:fieldId "description" :name "Description" :required true}
                                                  {:fieldId "labels" :name "Labels" :required true}]))]
    (is (thrown-with-msg? Exception #"Description \(description\), Labels \(labels\)"
                          (agent/preview-data (creation-runtime) {:action "create_item" :title "Bug"})))
    (is (agent/preview-data (creation-runtime)
                            {:action "create_item" :title "Bug" :description "Body" :labels ["bug"]}))))

(deftest create-metadata-pagination-checks-all-issue-types-and-fields
  (let [calls (atom [])]
    (with-redefs [jira/api! (fn [_ method path {:keys [startAt] :as query}]
                             (is (= :get method))
                             (swap! calls conj [path startAt])
                             (case [path startAt]
                               ["/issue/createmeta/APP/issuetypes" 0]
                               {:startAt 0 :total 2 :issueTypes [{:id "10000" :name "Other"}]}
                               ["/issue/createmeta/APP/issuetypes" 1]
                               {:startAt 1 :total 2 :issueTypes [{:id "10001" :name "Task"}]}
                               ["/issue/createmeta/APP/issuetypes/10001" 0]
                               {:startAt 0 :total 2 :fields [{:fieldId "summary" :name "Summary" :required true}]}
                               ["/issue/createmeta/APP/issuetypes/10001" 1]
                               {:startAt 1 :total 2 :fields [{:fieldId "customfield_10001" :name "Exchanges" :required true}]}))]
      (is (thrown-with-msg? Exception #"Exchanges"
                            (agent/preview-data (creation-runtime) {:action "create_item" :title "Bug"})))
      (is (= [0 1 0 1] (mapv second @calls))))))

(deftest create-metadata-errors-stop-preview-without-writing
  (doseq [page [{:startAt 0 :total 1 :issueTypes []}
               {:startAt 1 :total 2 :issueTypes [{:id "10001" :name "Task"}]}
               {:startAt 0 :total 0}]]
    (with-redefs [jira/api! (fn [& _] page)]
      (is (thrown-with-msg? Exception #"invalid or incomplete create metadata"
                            (agent/preview-data (creation-runtime) {:action "create_item" :title "Bug"})))))
  (with-redefs [jira/api! (fn [_ method _ _]
                           (is (= :get method))
                           (throw (ex-info "Create metadata permission denied." {:provider :jira :status 403})))]
    (is (thrown-with-msg? Exception #"permission denied"
                          (agent/preview-data (creation-runtime) {:action "create_item" :title "Bug"})))))

(deftest ambiguous-create-types-and-changed-type-ids-do-not-mutate
  (with-redefs [jira/api! (fn [& _] {:startAt 0 :total 2
                                    :issueTypes [{:id "1" :name "Task"} {:id "2" :name "Task"}]})]
    (is (thrown-with-msg? Exception #"unavailable or ambiguous"
                          (agent/preview-data (creation-runtime) {:action "create_item" :title "Bug"}))))
  (let [id (atom "10001")
        request {:action "create_item" :title "Bug"}]
    (with-redefs [jira/api! (fn [_ method path _]
                             (is (= :get method))
                             (if (= path "/issue/createmeta/APP/issuetypes")
                               {:startAt 0 :total 1 :issueTypes [{:id @id :name "Task"}]}
                               {:startAt 0 :total 0 :fields []}))]
      (let [preview (agent/preview-data (creation-runtime) request)]
        (reset! id "10002")
        (is (thrown-with-msg? Exception #"Approval does not match"
                              (agent/apply-data! (creation-runtime) request (:proposalId preview))))))))
