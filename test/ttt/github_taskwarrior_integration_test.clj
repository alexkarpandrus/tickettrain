(ns ttt.github-taskwarrior-integration-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.core :as core]
            [ttt.cli.agent :as agent]
            [ttt.config :as app-config]
            [ttt.platform.shell :as shell]
            [ttt.providers.forge :as forge]
            [ttt.providers.tracker :as tracker]
            [ttt.providers.tracker.taskwarrior :as taskwarrior]))

(def config
  {:tracker {:provider :taskwarrior}
   :forge {:provider :github}
   :change-request {:body-begin-marker "<!-- ttt:begin -->"
                    :body-end-marker "<!-- ttt:end -->"
                    :section-title "Taskwarrior"}})

(def existing-uuid "a360fc44-315c-4366-b70c-ea7e7520b749")

(defn native-task
  []
  {:id 7
   :uuid existing-uuid
   :description "Retry"
   :status "pending"
   :project "App"
   :tags ["existing" "Bug"]
   :details "Native vendor phone numbers"
   :annotations [{:entry "20260907T115900Z" :description "Exact user comment"}
                 {:entry "20260907T120000Z"
                  :description (str taskwarrior/annotation-prefix "Tracker body")}]})

(defn shell-stub
  [task* order forge-payload]
  (fn [& raw-args]
    (let [args (vec raw-args)]
      (cond
        (= ["task" "--version"] args) "3.5.0"
        (= ["task" "_get" "rc.data.location"] args) "/tmp/task-data"
        (= ["task" "_unique" "project"] args) "App"
        (= ["task" "export" "rc.json.array=on"] args)
        (json/generate-string
         [(or @task* {:uuid "catalog00-0000-0000-0000-000000000000"
                      :description "Catalog" :status "pending" :tags ["Bug"]})])
        (and (= "task" (first args))
             (= "export" (nth args (- (count args) 2))))
        (let [reference (second args)]
          (json/generate-string
           (if (= reference (str (:uuid @task*))) [@task*] [])))

        (= ["gh" "repo" "view" "--json" "nameWithOwner,defaultBranchRef"] args)
        (json/generate-string {:nameWithOwner "org/repo"
                               :defaultBranchRef {:name "main"}})

        (= ["gh" "pr" "view" "--json" "number,title,body,url,headRefName,baseRefName,state"] args)
        (json/generate-string {:number 7 :title "Retry" :body "User body"
                               :url "https://github.com/org/repo/pull/7"
                               :headRefName "retry" :baseRefName "main" :state "OPEN"})

        (= ["git" "rev-parse" "--abbrev-ref" "HEAD"] args) "retry"

        (= ["gh" "api"] (subvec args 0 2))
        (do
          (swap! order conj :forge)
          (reset! forge-payload
                  (json/parse-string
                   (slurp (nth args (inc (.indexOf args "--input")))) true))
          "{}")

        :else (throw (ex-info "Unexpected shell call" {:args args}))))))

(defn input-stub
  [task* order]
  (fn [input & args]
    (when-not (= ["task" "import" "-" "rc.confirmation=off"] (vec args))
      (throw (ex-info "Unexpected input shell call" {:args args})))
    (swap! order conj (if @task* :tracker :tracker-create))
    (reset! task* (assoc (first (json/parse-string input true)) :id 8))
    ""))

(deftest github-taskwarrior-applies-link-intent-at-native-boundaries
  (let [task* (atom (native-task))
        order (atom [])
        forge-payload (atom nil)]
    (with-redefs [shell/run (shell-stub task* order forge-payload)
                  shell/run-input (input-stub task* order)]
      (let [runtime (adapters/runtime config forge/registry tracker/registry)
            proposal (core/preview runtime {:action :link-existing
                                            :item-ref existing-uuid
                                            :labels ["Bug"]})]
        (is (= :taskwarrior (get-in proposal [:item :ref :provider])))
        (is (empty? @order))
        (core/apply! runtime proposal)))
    (is (= [:tracker :forge] @order))
    (is (= ["existing" "Bug"] (:tags @task*)))
    (is (= "Native vendor phone numbers" (:details @task*)))
    (is (= [{:entry "20260907T115900Z" :description "Exact user comment"}]
           (:annotations @task*)))
    (is (str/starts-with? (:tttDescription @task*) "Tracker body"))
    (is (str/includes? (taskwarrior/tracker-description @task*)
                       "https://github.com/org/repo/pull/7"))
    (is (= "[a360fc44] Retry" (:title @forge-payload)))
    (is (str/includes? (:body @forge-payload) "- Issue: a360fc44"))))

(deftest github-taskwarrior-translates-project-and-tags-on-create
  (let [task* (atom nil)
        order (atom [])
        forge-payload (atom nil)]
    (with-redefs [shell/run (shell-stub task* order forge-payload)
                  shell/run-input (input-stub task* order)]
      (let [runtime (adapters/runtime config forge/registry tracker/registry)
            proposal (core/preview runtime {:action :create-new
                                            :project-ref "App"
                                            :labels ["Bug"]})]
        (core/apply! runtime proposal)))
    (is (= [:tracker-create :forge] @order))
    (is (= "App" (:project @task*)))
    (is (= ["Bug"] (:tags @task*)))
    (is (str/includes? (:body @forge-payload) "## Taskwarrior"))))


(deftest approved-create-comment-and-completion-preserve-native-details-and-description
  (let [task* (atom nil) order (atom []) forge-payload (atom nil)
        native-run (shell-stub task* order forge-payload)
        apply-request (fn [request]
                        (let [preview (agent/run ["preview" "--request" (json/generate-string request)])]
                          (is (= 0 (:exit preview)))
                          (let [result (agent/run ["apply" "--request" (json/generate-string request)
                                                   "--approve" (get-in preview [:envelope :data :proposalId])])]
                            (is (= 0 (:exit result)))
                            result)))]
    (with-redefs [app-config/load-config (fn [& _] config)
                  shell/run-input (input-stub task* order)
                  shell/run (fn [& args]
                              (if (= "annotate" (nth args 2 nil))
                                (do (swap! task* update :annotations conj
                                           {:entry "20261008T120000Z" :description (last args)})
                                    "")
                                (apply native-run args)))]
      (apply-request {:action "create_item" :title "Review native details" :description "Managed body"})
      (swap! task* assoc :details "Native details" :custom_uda "Keep this"
             :entry "20260907T115800Z"
             :annotations [{:entry "20260907T115900Z" :description "Existing comment"}])
      (let [entry (:entry @task*) uuid (:uuid @task*)]
        (apply-request {:action "comment_item" :item uuid :body "New comment"})
        (let [annotations (:annotations @task*)
              result (apply-request {:action "update_item" :item uuid :state "completed"})]
          (is (= "completed" (get-in result [:envelope :data :item :state])))
          (is (= "Managed body" (:tttDescription @task*)))
          (is (= "Native details" (:details @task*)))
          (is (= "Keep this" (:custom_uda @task*)))
          (is (= entry (:entry @task*)))
          (is (= [{:entry "20260907T115900Z" :description "Existing comment"}
                  {:entry "20261008T120000Z" :description "New comment"}]
                 annotations (:annotations @task*))))))))


(deftest read-only-search-falls-back-without-weakening-selected-target-validation
  (let [malformed (assoc (native-task) :description "Repair inventory" :project "Other"
                         :tttDescription "Conflicting description")
        healthy (assoc (native-task) :id 8 :uuid "b360fc44-315c-4366-b70c-ea7e7520b749"
                       :description "Prepare inventory" :project "Healthy"
                       :annotations [] :tttDescription "Healthy body")
        native-run (shell-stub (atom malformed) (atom []) (atom nil))
        export (fn [_ & [reference]]
                 (if reference (filterv #(= reference (:uuid %)) [malformed healthy])
                     [malformed healthy]))
        search (fn [& options]
                 (agent/run (into ["search" "--kind" "item" "--query" "Repair"] options)))]
    (with-redefs [app-config/load-config (fn [& _] config)
                  shell/run (fn [& args]
                              (if (= ["task" "_unique" "project"] (vec args))
                                "Other\nHealthy" (apply native-run args)))
                  shell/run-input (fn [& _] (throw (ex-info "Search must not write" {})))
                  taskwarrior/export-tasks export]
      (let [unfiltered (search) filtered (search "--project" "Healthy")]
        (is (= 0 (:exit unfiltered) (:exit filtered)))
        (is (= "a360fc44" (get-in unfiltered [:envelope :data :candidates 0 :displayId])))
        (is (= ["b360fc44"] (mapv :displayId (get-in filtered [:envelope :data :candidates])))))
      (is (= "malformed-managed-section"
             (get-in (agent/run ["preview" "--request"
                                 (json/generate-string {:action "update_item" :item existing-uuid
                                                        :state "completed"})])
                     [:envelope :error :code])))
      (with-redefs [taskwarrior/export-tasks (fn [_ & [reference]]
                                             (if reference [] [malformed healthy]))]
        (is (= 0 (:exit (search "--project" "Healthy")))))
      (let [list-reads (atom 0)]
        (with-redefs [taskwarrior/export-tasks (fn [_ & [reference]]
                                               (if reference
                                                 (throw (ex-info "Provider unavailable"
                                                                 {:code :provider-unavailable}))
                                                 (do (swap! list-reads inc) [malformed healthy])))]
          (is (= "provider-unavailable" (get-in (search) [:envelope :error :code])))
          (is (= 1 @list-reads)))))))
