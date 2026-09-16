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
    (is (= "pending" (get-in item [:state :name])))
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
            labels [(taskwarrior/normalize-tag scope "bug")]
            item (taskwarrior/create-item-from-intent!
                  {} scope {:project project}
                  {:title "Retry" :description "Body" :labels labels})]
        (is (= "Retry" (:description @imported)))
        (is (= "App" (:project @imported)))
        (is (= ["bug"] (:tags @imported)))
        (is (= "Body" (:description item)))))))

(deftest resolve-labels-requires-existing-tags
  (with-redefs [taskwarrior/tags
                (fn [_ _] [(taskwarrior/normalize-tag scope "bug")])]
    (is (= ["bug"]
           (mapv :display-id
                 (taskwarrior/resolve-labels {} scope ["Bug"] scope))))
    (is (thrown-with-msg? Exception #"tag not found"
                          (taskwarrior/resolve-labels {} scope ["missing"] scope)))))


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


(deftest comments-on-tasks-use-annotations
  (let [request (atom nil)
        item {:ref (domain/identity :taskwarrior :tracker-item "uuid-1")}]
    (with-redefs [taskwarrior/task-run (fn [& args] (reset! request args))]
      (taskwarrior/comment-item! {} item "Looks good"))
    (is (= [{} "uuid-1" "annotate" "Looks good"] @request))))

(deftest neutral-adapter-declares-every-tracker-capability
  (let [adapter (taskwarrior/neutral-adapter {})]
    (is (= :taskwarrior (:provider adapter)))
    (is (= taskwarrior/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) taskwarrior/capabilities))))
