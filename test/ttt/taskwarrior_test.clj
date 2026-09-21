(ns ttt.taskwarrior-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.providers.tracker.taskwarrior :as taskwarrior]))

(def scope (domain/scope-identity :taskwarrior "/tmp/tasks"))
(def uuid "a360fc44-315c-4366-b70c-ea7e7520b749")

(def native-task
  {:id 7
   :uuid uuid
   :description "Retry sync"
   :status "pending"
   :project "App"
   :tags ["bug" "backend"]
   :annotations [{:entry "20260907T120000Z" :description "User note"}
                 {:entry "20260907T120100Z"
                  :description (str taskwarrior/annotation-prefix "Body\n\n## Pull requests")}]})

(deftest normalizes-task-with-project-tags-and-managed-description
  (let [item (taskwarrior/normalize-task scope native-task)]
    (is (= "a360fc44" (:display-id item)))
    (is (= uuid (get-in item [:ref :id])))
    (is (= "Retry sync" (:title item)))
    (is (= "Body\n\n## Pull requests" (:description item)))
    (is (= "open" (:state item)))
    (is (= "App" (get-in item [:project :display-id])))
    (is (= ["bug" "backend"] (mapv :display-id (:labels item))))
    (is (domain/entity-in-scope? item scope))))

(deftest description-update-preserves-task-data-and-user-annotations
  (let [updated (taskwarrior/upsert-description (assoc native-task :custom_uda "keep") "Updated")]
    (is (= "keep" (:custom_uda updated)))
    (is (= "User note" (get-in updated [:annotations 0 :description])))
    (is (= (str taskwarrior/annotation-prefix "Updated")
           (get-in updated [:annotations 1 :description])))
    (is (= "20260907T120100Z" (get-in updated [:annotations 1 :entry])))))

(deftest item-update-import-preserves-native-fields-and-existing-annotations
  (let [stored (atom (assoc native-task
                            :status "waiting"
                            :depends ["dependency-uuid"]
                            :due "20261001T120000Z"
                            :wait "20260930T120000Z"
                            :custom_uda "keep"))
        item (taskwarrior/normalize-task scope @stored)
        labels [(taskwarrior/normalize-tag scope "bug")
                (taskwarrior/normalize-tag scope "waiting")]]
    (with-redefs [taskwarrior/export-tasks (fn [_ _] [@stored])
                  taskwarrior/task-run-input (fn [_ input & _]
                                               (reset! stored (first (json/parse-string input true)))
                                               "")]
      (taskwarrior/update-item-from-intent! {} scope item {:description (:description item)
                                                           :labels labels}))
    (is (= "waiting" (:status @stored)))
    (is (= ["dependency-uuid"] (:depends @stored)))
    (is (= "20261001T120000Z" (:due @stored)))
    (is (= "20260930T120000Z" (:wait @stored)))
    (is (= "keep" (:custom_uda @stored)))
    (is (= "User note" (get-in @stored [:annotations 0 :description])))
    (is (= ["bug" "waiting"] (:tags @stored)))))

(deftest duplicate-managed-annotations-are-rejected
  (is (thrown-with-msg?
       Exception
       #"duplicate ttt description"
       (taskwarrior/upsert-description
        {:annotations [(taskwarrior/description-annotation "one")
                       (taskwarrior/description-annotation "two")]}
        "three"))))

(deftest create-imports-native-project-tags-and-description
  (let [imported (atom nil)]
    (with-redefs [taskwarrior/task-run-input
                  (fn [_ input & _]
                    (reset! imported (first (json/parse-string input true)))
                    "")
                  taskwarrior/export-tasks
                  (fn [_ reference]
                    (when (= reference (:uuid @imported))
                      [(assoc @imported :id 8)]))]
      (let [project (taskwarrior/normalize-project scope "App")
            labels (mapv #(taskwarrior/normalize-tag scope %) ["waiting" "blocked" "promised"])
            blocker {:ref (domain/identity :taskwarrior :tracker-item "dependency-uuid")
                     :display-id "dependen" :title "Dependency" :scopes [scope]}
            item (taskwarrior/create-item-from-intent!
                  {} scope {:project project}
                  {:title "Retry"
                   :description "Body"
                   :labels labels
                   :state "active"
                   :priority "urgent"
                   :due-at "2026-10-01T12:00:00Z"
                   :available-at "2026-09-30T12:00:00Z"
                   :blocked-by [blocker]})]
        (is (= "Retry" (:description @imported)))
        (is (= "App" (:project @imported)))
        (is (= "pending" (:status @imported)))
        (is (:start @imported))
        (is (= "H" (:priority @imported)))
        (is (= "20261001T120000Z" (:due @imported)))
        (is (= "20260930T120000Z" (:wait @imported)))
        (is (= "dependency-uuid" (:depends @imported)))
        (is (= ["waiting" "blocked" "promised"] (:tags @imported)))
        (is (= "Body" (:description item)))
        (is (= "active" (:state item)))
        (is (= "urgent" (:priority item)))
        (is (= "Dependency" (get-in item [:blocked-by 0 :title])))))))

(deftest resolve-projects-and-labels-allows-new-native-names
  (with-redefs [taskwarrior/projects
                (fn [_ _] [(taskwarrior/normalize-project scope "App")])
                taskwarrior/tags
                (fn [_ _] [(taskwarrior/normalize-tag scope "bug")])]
    (is (= "App" (:display-id (taskwarrior/resolve-project {} scope "app"))))
    (is (= "New Project" (:display-id (taskwarrior/resolve-project {} scope "New Project"))))
    (is (= ["bug" "waiting"]
           (mapv :display-id
                 (taskwarrior/resolve-labels {} scope ["Bug" "waiting"] scope))))))


(deftest project-and-tag-catalogs-use-native-values
  (let [calls (atom [])]
    (with-redefs [taskwarrior/task-run
                  (fn [_ & args]
                    (swap! calls conj (vec args))
                    "Active\nDormant")
                  taskwarrior/export-tasks
                  (fn [_] [{:tags ["bug" "backend"]}
                           {:tags ["bug"]}])]
      (is (= ["Active" "Dormant"]
             (mapv :display-id (taskwarrior/projects {} scope))))
      (is (= ["bug" "backend"]
             (mapv :display-id (taskwarrior/tags {} scope)))))
    (is (= [["_unique" "project"]] @calls))))

(deftest case-insensitive-name-fallback-must-be-unique
  (let [available [(taskwarrior/normalize-tag scope "Bug")
                   (taskwarrior/normalize-tag scope "bug")]]
    (is (= "Bug" (:display-id (taskwarrior/resolve-name available "Bug" :label))))
    (is (= :ambiguous-label
           (try
             (taskwarrior/resolve-name available "BUG" :label)
             (catch Exception ex (:code (ex-data ex))))))))

(deftest parent-tasks-are-explicitly-unsupported
  (is (= :unsupported-parent
         (try
           (taskwarrior/unsupported-parent! "anything")
           (catch Exception ex (:code (ex-data ex)))))))

(deftest taskrc-is-passed-as-a-native-cli-override
  (is (= ["task" "export" "rc:/tmp/taskrc"]
         (taskwarrior/command-args {:tracker {:taskrc "/tmp/taskrc"}} ["export"]))))

(deftest default-taskrc-follows-home-environment
  (let [original-home (System/getProperty "user.home")
        environment-home (System/getenv "HOME")
        fake-home (str original-home "-not-environment-home")]
    (try
      (System/setProperty "user.home" fake-home)
      (is (= (java.io.File. (or environment-home fake-home) ".taskrc")
             (taskwarrior/taskrc-file {})))
      (finally
        (System/setProperty "user.home" original-home)))))

(deftest setup-creates-an-empty-missing-taskrc
  (let [root (java.io.File/createTempFile "ttt-taskwarrior-" ".tmp")
        taskrc (java.io.File. root "nested/taskrc")
        app-config {:tracker {:taskrc (.getAbsolutePath taskrc)}}]
    (.delete root)
    (try
      (with-redefs [taskwarrior/task-version (constantly "3.5.0")
                    taskwarrior/task-run
                    (fn [_ & _]
                      (if (.exists taskrc)
                        "/tmp/tasks"
                        (throw (ex-info "task _get rc.data.location failed (exit 2): Configuration override rc.data.location"
                                        {:err (str "Configuration override rc.data.location\n"
                                                   "Cannot proceed without rc file.")}))))]
        (let [error (try
                      (taskwarrior/assert-ready! app-config)
                      (catch Exception ex ex))]
          (is (= "Taskwarrior has no configuration file. Run `ttt setup` to create an empty one automatically."
                 (.getMessage error)))
          (is (not (re-find #"Configuration override" (.getMessage error)))))
        (with-out-str (taskwarrior/setup app-config))
        (is (.exists taskrc))
        (is (zero? (.length taskrc))))
      (finally
        (.delete taskrc)
        (.delete (.getParentFile taskrc))
        (.delete root)))))

(deftest other-readiness-failures-keep-their-diagnostics
  (let [failure (ex-info "task _get rc.data.location failed (exit 1): database unavailable"
                         {:err "database unavailable"})
        error (with-redefs [taskwarrior/task-version (constantly "3.5.0")
                            taskwarrior/task-run (fn [& _] (throw failure))]
                (try
                  (taskwarrior/assert-ready! {})
                  (catch Exception ex ex)))]
    (is (= "Taskwarrior tracker requires a working `task` CLI and configuration."
           (.getMessage error)))
    (is (identical? failure (ex-cause error)))))


(deftest comments-on-tasks-use-annotations
  (let [request (atom nil)
        item {:ref (domain/identity :taskwarrior :tracker-item "uuid-1")}]
    (with-redefs [taskwarrior/task-run (fn [& args] (reset! request args))]
      (taskwarrior/comment-item! {} item "Looks good"))
    (is (= [{} "uuid-1" "annotate" "Looks good"] @request))))

(deftest list-items-exports-current-tasks-with-neutral-state)
  (let [calls (atom [])]
    (with-redefs [taskwarrior/configured-scope (constantly scope)
                  taskwarrior/export-tasks (fn [_]
                                             (swap! calls conj :export)
                                             [(assoc native-task :status "waiting")])]
      (let [item (first ((:list-items (taskwarrior/neutral-adapter {}))))]
        (is (= [:export] @calls))
        (is (= "a360fc44" (:display-id item)))
        (is (= "waiting" (:state item))))))

(deftest neutral-adapter-declares-every-tracker-capability
  (let [adapter (taskwarrior/neutral-adapter {})]
    (is (= :taskwarrior (:provider adapter)))
    (is (= taskwarrior/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) taskwarrior/capabilities))
    (is (= #{:item-lifecycle :item-priority :item-due-dates
              :item-availability :item-blockers}
           (:item-capabilities adapter)))))

(deftest translates-neutral-work-item-fields-without-losing-native-data
  (let [blocker-uuid "b460fc44-315c-4366-b70c-ea7e7520b750"
        blocker {:uuid blocker-uuid :description "Deploy dependency" :status "pending"}
        native (assoc native-task
                      :start "20260920T090000Z"
                      :priority "H"
                      :due "20260930T170000Z"
                      :wait "20260921T090000Z"
                      :depends [blocker-uuid])
        item (taskwarrior/normalize-task scope native {blocker-uuid blocker})
        cleared (taskwarrior/apply-work-item-intent
                 (assoc native :custom_uda "keep")
                 {:state "completed"
                  :priority nil
                  :due-at nil
                  :available-at nil
                  :blocked-by []})
        urgent (taskwarrior/apply-work-item-intent native {:priority "urgent"})
        waiting (taskwarrior/apply-work-item-intent native-task {:state "waiting"})]
    (is (= "active" (:state item)))
    (is (= "high" (:priority item)))
    (is (= "2026-09-30T17:00:00Z" (:due-at item)))
    (is (= "2026-09-21T09:00:00Z" (:available-at item)))
    (is (= [{:display-id "b460fc44" :title "Deploy dependency"}]
           (mapv #(select-keys % [:display-id :title]) (:blocked-by item))))
    (is (= "keep" (:custom_uda cleared)))
    (is (= "completed" (:status cleared)))
    (is (not (contains? cleared :priority)))
    (is (not (contains? cleared :due)))
    (is (not (contains? cleared :wait)))
    (is (empty? (:depends cleared)))
    (is (= "User note" (get-in cleared [:annotations 0 :description])))
    (is (= "urgent" (taskwarrior/normalize-priority urgent)))
    (is (= "waiting" (taskwarrior/normalize-state waiting)))))

(deftest ambiguous-taskwarrior-blocker-references-are-rejected
  (with-redefs [taskwarrior/tasks (fn [_ _]
                                   [(assoc (taskwarrior/normalize-task scope native-task)
                                           :display-id "one")
                                    (assoc (taskwarrior/normalize-task scope native-task)
                                           :display-id "two")])]
    (is (thrown-with-msg? Exception #"ambiguous"
                          (taskwarrior/resolve-item {} scope "Retry")))))
