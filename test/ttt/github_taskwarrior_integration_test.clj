(ns ttt.github-taskwarrior-integration-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.core :as core]
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
   :annotations [{:entry "20260907T120000Z"
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
