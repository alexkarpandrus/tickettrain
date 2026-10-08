;; Optional native smoke: bb --classpath src:test test/logseq_db_live.clj
;; Requires the official Logseq DB CLI. Creates and removes only its own graph
;; under the current directory. Does not open, switch, or modify existing graphs.
(require '[babashka.fs :as fs]
         '[ttt.adapters :as adapters]
         '[ttt.cli.agent :as agent]
         '[ttt.platform.shell :as shell]
         '[ttt.providers.tracker :as tracker]
         '[ttt.providers.tracker.logseq-db :as db])

(let [root (str (fs/create-temp-dir {:dir "." :prefix ".ttt-logseq-live-"}))
      graph "ttt-disposable-test"
      cfg {:tracker {:provider :logseq-db :graph graph :root-dir root}}
      cli (fn [& args] (apply shell/run "logseq" (concat args ["--root-dir" root "--graph" graph "--output" "json"])))
      apply-approved (fn [rt request]
                       (agent/apply-data! rt request (:proposalId (agent/preview-data rt request))))]
  (try
    (cli "graph" "create")
    ;; Page creation is not a ttt operation. This page exists only in this new graph.
    (cli "upsert" "page" "--page" "ttt-sandbox")
    (let [rt {:config cfg :tracker (adapters/build cfg :tracker tracker/registry)}
          a (:tracker rt)
          scope ((:configured-scope a))
          _ (assert (some #(= "ttt-sandbox" (:title %)) (:candidates (agent/search-data a {:kind "project" :query "ttt-sandbox"}))))
          tasks #((:list-items a) (constantly true) 100)
          req {:action "create_item" :project "ttt-sandbox" :title "Native task"
               :description "Original body\n" :comment "#Task original request"}
          p (agent/preview-data rt req)]
      (assert (empty? (tasks)))
      (assert (try (agent/apply-data! rt req "lp2_wrong") false (catch Exception _ true)))
      (assert (empty? (tasks)))
      (let [native db/native! tag-default (atom :not-observed)
            created (with-redefs [db/native! (fn [cfg scope & args]
                                             (let [result (apply native cfg scope args)]
                                               (when (some #(= "--update-tags=[\"Task\"]" %) args)
                                                 (reset! tag-default
                                                         (get-in (first (:result (native cfg scope "query" "--query" db/pull-query
                                                                                       "--inputs" (pr-str [(:result result)]))))
                                                                 [:logseq.property/status :db/ident])))
                                               result))]
                      (:item (agent/apply-data! rt req (:proposalId p))))
            uuid (get-in created [:identity :id])]
        (assert (nil? @tag-default))
        ;; Priority is not a ttt mutation capability. Seed an unrelated native
        ;; property on this owned task to test that content/state edits retain it.
        (cli "upsert" "task" (str "--uuid=" uuid) "--priority=high")
        (assert (= "Original body\n" (:description created)))
        (assert (= 1 (count (tasks))))
        (assert (= uuid (:display-id ((:resolve-item a) uuid))))
        (assert (= uuid (get-in (agent/search-data a {:kind "item" :query "Native task"}) [:candidates 0 :identity :id])))
        (let [priority-query "[:find [(pull ?id [{:logseq.property/priority [:db/ident]}]) ...] :in $ [?id ...]]"
              priority (fn [] (:logseq.property/priority
                               (first (:result (db/native! cfg scope "query" "--query" priority-query
                                                          "--inputs" (pr-str [[(:logseq/db-id ((:resolve-item a) uuid))]]))))))
              original (priority)
              native-status {"open" "logseq.property/status.todo" "active" "logseq.property/status.doing"
                             "waiting" "logseq.property/status.in-review" "completed" "logseq.property/status.done"
                             "canceled" "logseq.property/status.canceled"}]
          (assert (seq original))
          (doseq [fields [{:title "Edited native task" :description "Edited body\nsecond line" :state "active"}
                          {:state "waiting"} {:state "completed"} {:state "canceled"} {:state "open"}
                          {:description " "} {:description "Trailing newline\n\n"} {:description ""}]]
            (let [item (:item (apply-approved rt (merge {:action "update_item" :item uuid} fields)))]
              (assert (every? (fn [[k v]] (= v (get item k))) fields))
              (when-let [state (:state fields)]
                (assert (= (native-status state) (:logseq/status ((:resolve-item a) uuid)))))
              (assert (= original (priority))))))
        (doseq [body ["TODO comment" "#Task comment" "#Todo comment"
                      "Ordinary comment\nsecond line" "--status=done\n$(literal text)"
                      "  leading and trailing whitespace\n\n"]]
          (apply-approved rt {:action "comment_item" :item uuid :body body})
          (assert (= 1 (count (tasks)))))
        (let [task ((:resolve-item a) uuid)
              children (:result (db/native! cfg scope "query" "--query"
                                            "[:find (pull ?b [:block/title]) :in $ ?parent :where [?b :block/parent ?parent]]"
                                            "--inputs" (pr-str [(:logseq/db-id task)])))]
          (assert (= 7 (count children)))
          (assert (some #(= "--status=done\n$(literal text)" (:block/title (first %))) children))
          (assert (some #(= "  leading and trailing whitespace\n\n" (:block/title (first %))) children)))


        (let [ids (-> (cli "upsert" "block" (str "--target-uuid=" uuid) "--pos=first-child"
                           "--blocks=[{:block/title \"\"}]")
                      (json/parse-string true) (get-in [:data :result]))
              before (first (:result (db/native! cfg scope "query" "--query" db/pull-query "--inputs" (pr-str [ids]))))]
          (apply-approved rt {:action "comment_item" :item uuid :body "After an empty child"})
          (let [after (first (:result (db/native! cfg scope "query" "--query" db/pull-query "--inputs" (pr-str [ids]))))]
            (assert (= before after))))
        ;; Independent membership oracle: these are real native class relations,
        ;; not fixture responses selected by the production query string.
        (cli "upsert" "tag" "--name" "OwnedTask")
        (let [tag-id (-> (cli "query" "--query" "[:find ?id . :where [?id :block/title \"OwnedTask\"]]")
                         (json/parse-string true) (get-in [:data :result]))
              _ (cli "upsert" "page" (str "--id=" tag-id)
                     "--update-properties={:logseq.property.class/extends [[:db/ident :logseq.class/Task]]}")
              page-uuid (:display-id ((:resolve-project a) "ttt-sandbox"))
              insert (fn [text tags]
                       (-> (cli "upsert" "block" (str "--target-uuid=" page-uuid)
                                "--pos=last-child" (str "--content=" text) (str "--update-tags=" tags))
                           (json/parse-string true) (get-in [:data :result])))
              row (fn [ids] (first (:result (db/native! cfg scope "query" "--query" db/pull-query
                                                       "--inputs" (pr-str [ids])))))
              inherited (row (insert "Inherited task" "[\"OwnedTask\"]"))
              ordinary (row (insert "Ordinary note" "[]"))
              empty-task (row (-> (cli "upsert" "block" (str "--target-uuid=" page-uuid)
                                      "--pos=last-child" "--blocks=[{:block/title \"\"}]" "--update-tags=[\"Task\"]")
                                 (json/parse-string true) (get-in [:data :result])))]
          (assert (= "Inherited task" (:title ((:resolve-item a) (str (:block/uuid inherited))))))
          (assert (= "" (:title ((:resolve-item a) (str (:block/uuid empty-task))))))
          (assert (some #(= (str (:block/uuid empty-task)) (:display-id %)) ((:list-items a) (constantly true) 1000)))
          (apply-approved rt {:action "comment_item" :item (str (:block/uuid empty-task)) :body "Owned empty task comment"})

          (let [ids (-> (cli "upsert" "block" "--target-page=ttt-sandbox" "--pos=first-child"
                             "--blocks=[{:block/title \"\"}]")
                        (json/parse-string true) (get-in [:data :result]))
                before (first (:result (db/native! cfg scope "query" "--query" db/pull-query "--inputs" (pr-str [ids]))))
                next-item (:item (apply-approved rt {:action "create_item" :project "ttt-sandbox" :title "Second native task" :description " "}))]
            (assert (some? before))
            (assert (not= (str (:block/uuid before)) (get-in next-item [:identity :id])))
            (assert (= before (first (:result (db/native! cfg scope "query" "--query" db/pull-query "--inputs" (pr-str [ids]))))))
            (assert (= " " (:description next-item)))
            (assert (not= (str (:block/uuid empty-task)) (get-in next-item [:identity :id])))
            (assert (= "" (:title ((:resolve-item a) (str (:block/uuid empty-task)))))))
          (assert (nil? ((:resolve-item a) (str (:block/uuid ordinary)))))
          (let [error (try (agent/preview-data rt {:action "update_item" :item (str (:block/uuid ordinary))
                                                  :title "Must not change"})
                           (catch Exception e e))]
            (assert (instance? Exception error))
            (assert (= "Ordinary note" (:block/title (row [(:db/id ordinary)]))))))

        ;; Hydrate more than one real 200-row batch, including inherited/empty tasks.
        (let [page-uuid (:display-id ((:resolve-project a) "ttt-sandbox"))
              blocks (mapv (fn [i] {:block/title (str "Paging task " i)}) (range 201))]
          (cli "upsert" "block" (str "--target-uuid=" page-uuid) "--pos=first-child"
               (str "--blocks=" (pr-str blocks)) "--update-tags=[\"Task\"]")
          (let [items ((:list-items a) (constantly true) 1000)
                paging (filter #(str/starts-with? (:title %) "Paging task ") items)]
            (assert (= 201 (count paging)))
            (assert (= 205 (count items)))
            (assert (= (set (map #(str "Paging task " %) (range 201))) (set (map :title paging))))))
        ;; Stop and reload through the native CLI to check persistent UUID/content.
        (cli "server" "stop")
        (let [reloaded ((:resolve-item a) uuid)]
          (assert (= "Edited native task" (:title reloaded)))
          (assert (= "" (:description reloaded)))
          (assert (= "open" (:state reloaded))))
        (assert (= 205 (count ((:list-items a) (constantly true) 1000))))
        (println "PASS native Logseq DB: exact approval, creation without empty-task reuse, bounded page/UUID lookup, title/body edits, native states, priority preservation, inert comments, inherited/empty task discovery, 205-task batching, and persistence.")))
    (finally
      (try (cli "server" "stop") (finally (fs/delete-tree root))))))
