(ns ttt.logseq-db-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.core :as core]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]
            [ttt.providers.tracker :as tracker]
            [ttt.providers.tracker.logseq-db :as db]))

(def cfg {:tracker {:provider :logseq-db :graph "ttt-fixture" :root-dir "/tmp/ttt-db-unit"}})
(def graph-id "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
(def page-id "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
(def task-id "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
(def page {:db/id 1 :block/uuid page-id :block/title "Native page"})

(defn native-state []
  {:graph graph-id :pages {page-id page}
   :tasks {task-id {:db/id 10 :block/uuid task-id :block/title "Existing\nBody"
                   :block/page page :logseq.property/status {:db/ident "logseq.property/status.backlog"}
                   :fixture/property "keep"}}
   :comments {} :next-id 100})

(defn options [args]
  (loop [args args result {}]
    (if-let [arg (first args)]
      (if (str/includes? arg "=")
        (let [[k v] (str/split arg #"=" 2)] (recur (rest args) (assoc result k v)))
        (recur (nnext args) (assoc result arg (second args))))
      result)))

(defn cli-stub [state calls]
  (fn [& args]
    (is (= "logseq" (first args)))
    (let [[_ command kind & flags] args
          flags (if (= command "query") (cons kind flags) flags)
          opts (options flags)
          block-content (when-let [blocks (get opts "--blocks")] (:block/title (first (edn/read-string blocks))))
          _ (is (= "ttt-fixture" (get opts "--graph")))
          _ (is (= (db/root-dir cfg) (get opts "--root-dir")))
          _ (is (= "json" (get opts "--output")))
          _ (swap! calls conj {:command command :kind kind :options opts})
          data
          (case command
            "graph" {:kv {:logseq.kv/db-type "db" :logseq.kv/local-graph-uuid (:graph @state)}}
            "list" (let [rows (sort-by :db/id (vals (get @state (if (= kind "task") :tasks :pages))))]
                     {:items (mapv #(select-keys % [:db/id])
                                   (take (Long/parseLong (get opts "--limit"))
                                         (drop (Long/parseLong (get opts "--offset")) rows)))})
            "query" (let [inputs (edn/read-string (get opts "--inputs"))
                          rows (concat (vals (:pages @state)) (vals (:tasks @state)))]
                      (cond
                        (= db/task-ids-query (get opts "--query"))
                        {:result (mapv :db/id (vals (:tasks @state)))}
                        (= db/page-query (get opts "--query"))
                        {:result (vec (filter #(or (= (str (first inputs)) (:block/uuid %))
                                                    (= (second inputs) (:block/title %))) (vals (:pages @state))))}
                        (= db/uuid-page-query (get opts "--query"))
                        {:result (vec (filter #(= (str (first inputs)) (:block/uuid %)) (vals (:pages @state))))}
                        (= db/uuid-task-query (get opts "--query"))
                        (do (is (= db/task-class-rules (first inputs)))
                            {:result (vec (filter #(= (str (second inputs)) (:block/uuid %)) (vals (:tasks @state))))})
                        (= db/pull-query (get opts "--query"))
                        {:result (mapv (fn [id] (first (filter #(= id (:db/id %)) rows))) (first inputs))}
                        :else (throw (ex-info "Unexpected native query" {}))))
            "upsert"
            (if-let [uuid (get opts "--uuid")]
              (do
                (if (= kind "task")
                  (do (is (nil? (get opts "--content")))
                      (swap! state assoc-in [:tasks uuid :logseq.property/status]
                             {:db/ident (str "logseq.property/status." (get opts "--status"))}))
                  (swap! state assoc-in [:tasks uuid :block/title] (get opts "--content")))
                {:result [uuid]})
              (let [uuid (str (java.util.UUID/randomUUID)) id (:next-id @state)]
                (swap! state update :next-id inc)
                (if (get opts "--update-tags")
                  (do (is (= "block" kind))
                      (is (= "[\"Task\"]" (get opts "--update-tags")))
                      (is (= page-id (get opts "--target-uuid")))
                      (swap! state assoc-in [:tasks uuid]
                             {:db/id id :block/uuid uuid :block/title block-content :block/page page
                              :logseq.property/status nil}))
                  (do (is (= "first-child" (get opts "--pos")))
                      (swap! state assoc-in [:comments uuid]
                             {:parent (get opts "--target-uuid") :content block-content})))
                {:result [id]})))]
      (json/generate-string {:status "ok" :data data}))))

(defn runtime [] {:config cfg :tracker (adapters/build cfg :tracker tracker/registry)})
(defn writes [calls] (filter #(= "upsert" (:command %)) @calls))
(defn apply-request [rt request]
  (agent/apply-data! rt request (:proposalId (agent/preview-data rt request))))

(deftest native-db-approved-lifecycle-and-inert-comments
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [rt (runtime) req {:action "create_item" :project "Native page" :title "New task" :description "New body"}
            before @state p (agent/preview-data rt req)]
        (is (= before @state))
        (is (empty? (writes calls)))
        (is (thrown? Exception (agent/apply-data! rt req "lp2_wrong")))
        (is (empty? (writes calls)))
        (let [created (:item (agent/apply-data! rt req (:proposalId p))) uuid (get-in created [:identity :id])]
          (swap! state assoc-in [:tasks uuid :fixture/property] "keep")
          (is (= "New body" (:description created)))
          (is (= "logseq-db" (get-in created [:identity :provider])))
          (doseq [neutral (keys db/native-states)]
            (is (= neutral (:state (:item (apply-request rt {:action "update_item" :item uuid :state neutral}))))))
          (let [item (:item (apply-request rt {:action "update_item" :item uuid :title "Edited" :description "Body\nsecond line"}))]
            (is (= "Edited" (:title item)))
            (is (= "Body\nsecond line" (:description item))))
          (doseq [body ["TODO comment" "#Task comment" "#Todo comment" "--status=done\n$(not a shell)"]]
            (apply-request rt {:action "comment_item" :item uuid :body body}))
          (is (= 2 (count (:tasks @state))))
          (is (= 4 (count (:comments @state))))
          (is (= "keep" (get-in @state [:tasks uuid :fixture/property])))
          (is (= "logseq.property/status.backlog" (get-in @state [:tasks task-id :logseq.property/status :db/ident]))))))))

(deftest all-six-native-states-and-pagination
  (let [tasks (into {} (map-indexed (fn [i [status _]]
                                    (let [uuid (str (java.util.UUID/randomUUID))]
                                      [uuid {:db/id (+ 10 i) :block/uuid uuid :block/title (str "Task " i)
                                             :block/page page :logseq.property/status {:db/ident status}}]))
                                  [["logseq.property/status.backlog" "open"] ["logseq.property/status.todo" "open"]
                                   ["logseq.property/status.doing" "active"] ["logseq.property/status.in-review" "waiting"]
                                   ["logseq.property/status.done" "completed"] ["logseq.property/status.canceled" "canceled"]]))
        state (atom (assoc (native-state) :tasks tasks)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls) db/page-size 2]
      (let [a (:tracker (runtime)) items ((:list-items a) (constantly true) 20)
            wanted (:display-id (last items))]
        (is (= 6 (count items)))
        (is (= ["open" "open" "active" "waiting" "completed" "canceled"] (mapv :state items)))
        (is (= #{"open" "active" "waiting" "completed" "canceled"} (set (map :state items))))
        (is (= wanted (:display-id ((:resolve-item a) wanted))))
        (is (= [wanted] (mapv :display-id ((:list-items a) #(= wanted (:display-id %)) 1))))))))

(deftest same-neutral-state-retains-native-backlog-and-content-edits-retain-properties
  (let [state (atom (assoc-in (native-state) [:tasks task-id :block/title] "Existing\r\nBody")) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [rt (runtime)]
        (apply-request rt {:action "update_item" :item task-id :state "open"})
        (is (empty? (writes calls)))
        (apply-request rt {:action "update_item" :item task-id :state "active"})
        (is (= "Existing\r\nBody" (get-in @state [:tasks task-id :block/title])))
        (is (= ["task"] (mapv :kind (writes calls))))
        (swap! state assoc-in [:tasks task-id :logseq.property/status :db/ident] "logseq.property/status.backlog")
        (apply-request rt {:action "update_item" :item task-id :description "New body"})
        (is (= "keep" (get-in @state [:tasks task-id :fixture/property])))
        (is (= "logseq.property/status.backlog" (get-in @state [:tasks task-id :logseq.property/status :db/ident])))))))

(deftest stale-item-and-graph-guard-and-foreign-references
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [rt (runtime) a (:tracker rt) item ((:resolve-item a) task-id)
            req {:action "update_item" :item task-id :description "Approved"}
            approval (:proposalId (agent/preview-data rt req))]
        (swap! state assoc-in [:tasks task-id :block/title] "External edit")
        (is (thrown? Exception (agent/apply-data! rt req approval)))
        (is (thrown? Exception ((:update-item! a) item {:description "Approved"})))
        (is (thrown? Exception ((:resolve-item a)
                               (domain/identity-key (domain/contained-identity :logseq-db :tracker-item page-id task-id)))))
        (swap! state assoc :graph page-id)
        (is (thrown? Exception ((:configured-scope a))))
        (is (empty? (writes calls)))))))

(deftest partial-native-creation-is-not-retried
  (let [state (atom (native-state)) calls (atom []) send (cli-stub state calls)]
    (with-redefs [shell/run (fn [& args]
                             (let [result (apply send args)]
                               (when (= ["upsert" "task"] (vec (take 2 (rest args))))
                                 (throw (ex-info "Interrupted response" {})))
                               result))]
      (let [rt (runtime) req {:action "create_item" :project "Native page" :title "One task"}
            error (try (apply-request rt req) (catch Exception e e))]
        (is (= :logseq-create-outcome-unknown (:code (ex-data error))))
        (is (= 2 (count (:tasks @state))))
        (is (= 2 (count (writes calls))))))))

(deftest unsupported-fields-and-missing-page-fail-before-write
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [rt (runtime)]
        (doseq [req [{:action "create_item" :title "Needs a page"}
                     {:action "create_item" :title "Task" :project "Native page" :priority "high"}
                     {:action "update_item" :item task-id :title "First\nSecond"}
                     {:action "update_item" :item task-id :blockedBy []}]]
          (is (thrown? Exception (agent/preview-data rt req))))
        (is (thrown? Exception (agent/preview-data (update-in rt [:tracker :item-capabilities] disj :item-titles)
                              {:action "update_item" :item task-id :title "Unsupported"})))
        (is (empty? (writes calls)))))))

(deftest settings-and-error-privacy
  (is (= {:tracker {:graph "Graph" :root-dir "/tmp/root"}}
         (config/env-overrides cfg {"LOGSEQ_DB_GRAPH" "Graph" "LOGSEQ_ROOT_DIR" "/tmp/root"})))
  (doseq [settings [{:graph 12} {:graph "Graph" :root-dir ""}]]
    (is (thrown? Exception (db/assert-ready! {:tracker settings}))))
  (with-redefs [shell/run (fn [& args] (throw (ex-info (str "secret " args) {:out "secret" :err "secret"})))]
    (let [error (try (db/request! cfg "upsert" "block" "--content=secret") (catch Exception e e))]
      (is (not (str/includes? (.getMessage error) "secret")))
      (is (nil? (.getCause error)))
      (is (nil? (:args (ex-data error)))))))

(deftest native-create-response-must-identify-the-created-task
  (doseq [ids [nil [] [101 102] ["untyped-id"] [10]]]
    (let [state (atom (native-state)) calls (atom []) send (cli-stub state calls)]
      (with-redefs [shell/run (fn [& args]
                               (let [result (apply send args)]
                                 (if (some #(str/starts-with? % "--update-tags=") args)
                                   (json/generate-string {:status "ok" :data {:result ids}})
                                   result)))]
        (let [error (try (apply-request (runtime) {:action "create_item" :project "Native page" :title "New task"})
                         (catch Exception e e))]
          (is (= :logseq-create-outcome-unknown (:code (ex-data error))))
          (is (= 1 (count (writes calls))))
          (is (= 2 (count (:tasks @state)))))))))

(deftest comment-only-update-does-not-rewrite-task-content
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (apply-request (runtime) {:action "update_item" :item task-id :comment "Native comment"})
      (is (= ["block"] (mapv :kind (writes calls))))
      (is (= "Existing\nBody" (get-in @state [:tasks task-id :block/title]))))))

(deftest approved-page-uuid-cannot-fall-back-to-a-page-title
  (let [state (atom (native-state)) calls (atom []) replacement-id "dddddddd-dddd-4ddd-8ddd-dddddddddddd"]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [a (:tracker (runtime)) approved ((:resolve-project a) page-id)]
        (swap! state assoc :pages {replacement-id (assoc page :block/uuid replacement-id :block/title page-id)})
        (let [error (try ((:create-item! a) {:project approved} {:title "Approved only"})
                         (catch Exception e e))]
          (is (= :stale-proposal (:code (ex-data error))))
          (is (empty? (writes calls))))))))

(deftest exact-uuid-lookup-does-not-scan-unrelated-tasks
  (let [state (atom (native-state)) calls (atom [])]
    (swap! state update :tasks merge
           (into {} (for [i (range 1000)]
                      [(str (java.util.UUID/randomUUID))
                       {:db/id (+ 100 i) :block/uuid (str (java.util.UUID/randomUUID))
                        :block/title "Unrelated" :block/page page
                        :logseq.property/status {:db/ident "logseq.property/status.todo"}}])))
    (with-redefs [shell/run (cli-stub state calls)]
      (let [a (:tracker (runtime))]
        (reset! calls [])
        (is (= task-id (:display-id ((:resolve-item a) (str/upper-case task-id)))))
        (is (= ["graph" "query"] (mapv :command @calls)))
        (is (nil? ((:resolve-item a) page-id)))))))

(deftest native-status-only-changes-block-stale-writes
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [a (:tracker (runtime)) item ((:resolve-item a) task-id)]
        ;; Backlog -> Todo leaves the neutral state open but changes the snapshot.
        (swap! state assoc-in [:tasks task-id :logseq.property/status :db/ident] "logseq.property/status.todo")
        (doseq [f [#((:update-item! a) item {:description "Stale"})
                   #((:comment-item! a) item "Stale comment")]]
          (let [error (try (f) (catch Exception e e))]
            (is (= :stale-proposal (:code (ex-data error))))))
        (is (empty? (writes calls)))))))

(deftest combined-content-status-update-rechecks-native-status-between-writes
  (let [state (atom (native-state)) calls (atom []) send (cli-stub state calls)]
    (with-redefs [shell/run (fn [& args]
                             (let [result (apply send args)]
                               (when (and (= ["upsert" "block"] (vec (take 2 (rest args))))
                                          (some #(= (str "--uuid=" task-id) %) args))
                                 (swap! state assoc-in [:tasks task-id :logseq.property/status :db/ident]
                                        "logseq.property/status.todo"))
                               result))]
      (let [error (try (apply-request (runtime) {:action "update_item" :item task-id
                                                :description "Written body" :state "completed"})
                       (catch Exception e e))]
        (is (= :stale-proposal (:code (ex-data error))))
        (is (= ["block"] (mapv :kind (writes calls))))
        (is (= "logseq.property/status.todo" (get-in @state [:tasks task-id :logseq.property/status :db/ident])))))))

(deftest explicit-body-replacement-requires-a-declared-capability
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [rt (update-in (runtime) [:tracker :item-capabilities] disj :item-descriptions)]
        (doseq [body ["Replacement" ""]]
          (let [error (try (agent/preview-data rt {:action "update_item" :item task-id :description body})
                           (catch Exception e e))]
            (is (= :unsupported-capability (:code (ex-data error))))))
        (is (empty? (writes calls)))))))

(deftest linked-creation-validates-native-intent-during-preview
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [source {:change-request {:title "Title" :url "https://forge.invalid/pr/1" :state :open}}
            error (try (core/preview-tracker-link (runtime) source {:action :create-new})
                       (catch Exception e e))]
        (is (= :invalid-context (:code (ex-data error))))
        (is (empty? (writes calls)))))))

(deftest interrupted-native-creation-without-status-remains-readable
  (let [state (atom (native-state)) calls (atom []) send (cli-stub state calls)]
    (with-redefs [shell/run (fn [& args]
                             (if (= ["upsert" "task"] (vec (take 2 (rest args))))
                               (throw (ex-info "Before status assignment" {}))
                               (apply send args)))]
      (let [rt (runtime) error (try (apply-request rt {:action "create_item" :project "Native page" :title "Recover me"})
                                   (catch Exception e e))
            a (:tracker rt) tasks ((:list-items a) (constantly true) 10)
            incomplete (first (filter #(= "Recover me" (:title %)) tasks))]
        (is (= :logseq-create-outcome-unknown (:code (ex-data error))))
        (is (= 2 (count tasks)))
        (is (nil? (:state incomplete)))
        (is (= incomplete ((:resolve-item a) (:display-id incomplete))))
        (is (= 1 (count (writes calls))))))))

(deftest page-resolution-and-creation-do-not-enumerate-unrelated-pages
  (let [state (atom (native-state)) calls (atom [])]
    (swap! state update :pages merge
           (into {} (for [i (range 10000)]
                      (let [uuid (str (java.util.UUID/randomUUID))]
                        [uuid {:db/id (+ 10000 i) :block/uuid uuid :block/title (str "Unrelated " i)}]))))
    (with-redefs [shell/run (cli-stub state calls)]
      (apply-request (runtime) {:action "create_item" :project page-id :title "Bound destination"})
      (is (not-any? #(= "list" (:command %)) @calls))
      (is (< (count @calls) 30)))))

(deftest edn-insertions-preserve-creation-and-comment-whitespace
  (let [state (atom (native-state)) calls (atom [])]
    (with-redefs [shell/run (cli-stub state calls)]
      (let [created (:item (apply-request (runtime) {:action "create_item" :project page-id :title "Task" :description "Body\n"}))
            uuid (get-in created [:identity :id])
            body "  comment\n\n"]
        (is (= "Body\n" (:description created)))
        (apply-request (runtime) {:action "comment_item" :item uuid :body body})
        (is (= [body] (mapv :content (vals (:comments @state)))))
        (is (every? #(string? (get-in % [:options "--blocks"]))
                    (filter #(= "block" (:kind %)) (writes calls))))))))

(deftest replaced-db-graph-blocks-bound-lookups-before-native-query
  (doseq [lookup [:resolve-project :resolve-item]]
    (let [state (atom (native-state)) calls (atom [])]
      (with-redefs [shell/run (cli-stub state calls)]
        (let [a (:tracker (runtime))]
          (reset! calls [])
          (swap! state assoc :graph page-id)
          (let [error (try ((lookup a) (if (= lookup :resolve-project) "Native page" task-id))
                           (catch Exception e e))]
            (is (= :tracker-scope-mismatch (:code (ex-data error))))
            (is (not-any? #(= "query" (:command %)) @calls))
            (is (empty? (writes calls)))))))))

(deftest replaced-db-graph-between-read-and-write-prevents-updates-and-comments
  (doseq [operation [:update-item! :comment-item!]]
    (let [state (atom (native-state)) before @state calls (atom []) armed (atom false)
          send (cli-stub state calls)]
      (with-redefs [shell/run (fn [& args]
                              (let [result (apply send args)]
                                (when (and @armed (= "query" (second args)))
                                  (swap! state assoc :graph page-id))
                                result))]
        (let [a (:tracker (runtime)) item ((:resolve-item a) task-id)]
          (reset! calls [])
          (reset! armed true)
          (let [error (try ((operation a) item (if (= operation :update-item!) {:description "Approved"} "Approved comment"))
                           (catch Exception e e))]
            (is (= :tracker-scope-mismatch (:code (ex-data error))))
            (is (= 1 (count (filter #(= "query" (:command %)) @calls))))
            (is (empty? (writes calls)))
            (is (= (:tasks before) (:tasks @state)))
            (is (= (:comments before) (:comments @state)))))))))

(deftest native-status-assigned-after-insertion-is-not-overwritten
  (let [state (atom (native-state)) calls (atom []) new-id (atom nil) send (cli-stub state calls)]
    (with-redefs [shell/run (fn [& args]
                            (let [result (apply send args)]
                              (when (some #(str/starts-with? % "--update-tags=") args)
                                (reset! new-id (first (remove #{task-id} (keys (:tasks @state)))))
                                (swap! state assoc-in [:tasks @new-id :logseq.property/status]
                                       {:db/ident "logseq.property/status.done"}))
                              result))]
      (let [rt (runtime) request {:action "create_item" :project "Native page" :title "Approved" :state "active"}
            error (try (apply-request rt request) (catch Exception e e))]
        (is (= :logseq-create-outcome-unknown (:code (ex-data error))))
        (is (= "logseq.property/status.done" (get-in @state [:tasks @new-id :logseq.property/status :db/ident])))
        (is (= ["block"] (mapv :kind (writes calls))))))))
