(ns ttt.logseq-test
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ttt.adapters :as adapters]
            [ttt.cli.agent :as agent]
            [ttt.config :as config]
            [ttt.core :as core]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]
            [ttt.provider-integration-test :as integration]
            [ttt.providers.forge :as forge]
            [ttt.providers.tracker :as tracker]
            [ttt.providers.tracker.logseq :as logseq]))

(def app-config {:tracker {:provider :logseq :graph "/tmp/logseq-graph" :token "test-token"}
                 :forge {:provider :github}})
(def task-id "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
(def note-id "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
(def other-id "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
(def journal-id "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
(def writes #{"logseq.Editor.createPage" "logseq.Editor.appendBlockInPage"
              "logseq.Editor.updateBlock" "logseq.Editor.insertBlock"})

(defn native-state []
  {:graph {:path "/tmp/logseq-graph" :name "Notes"}
   :pages {1 {:id 1 :uuid journal-id :journal? true :journalDay 20260921}
           2 {:id 2 :uuid "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
              :journal? false :name "Ordinary page"}}
   :blocks {task-id {:id 10 :uuid task-id :page {:id 1}
                     :content (str "TODO Existing task\nKeep this detail\nid:: " task-id)
                     :properties {:id task-id :custom "keep"}}
            note-id {:id 11 :uuid note-id :page {:id 1} :content "Unrelated journal note"}
            other-id {:id 12 :uuid other-id :page {:id 2} :content "TODO Not a journal task"}}})

(defn block-tree [state page-id]
  (let [blocks (filter #(= page-id (get-in % [:page :id])) (vals (:blocks state)))]
    (letfn [(children [block]
              (assoc block :children
                     (mapv children (filter #(= (:uuid block) (:fixture-parent %)) blocks))))]
      (mapv children (remove :fixture-parent blocks)))))

(defn http-stub [state calls order]
  (fn [url options]
    (is (= "http://127.0.0.1:12315/api" url))
    (is (= "Bearer test-token" (get-in options [:headers "Authorization"])))
    (is (identical? logseq/api-client (:client options)))
    (let [{:keys [method args]} (json/parse-string (:body options) true)
          _ (swap! calls conj [method args])
          _ (when (contains? writes method) (swap! order conj :tracker))
          result
          (case method
            "logseq.App.getCurrentGraph" (:graph @state)
            "logseq.Editor.getAllPages" (vec (vals (:pages @state)))

            "logseq.DB.datascriptQuery"
            (let [query (edn/read-string (first args))
                  markers (second (first (nth query 4)))]
              (is (= (quote [:find ?uuid :where [?block :block/marker ?marker]])
                     (subvec query 0 4)))
              (is (= #{"TODO" "LATER" "NOW" "DOING" "STARTED" "IN-PROGRESS" "WAIT" "WAITING" "DONE" "CANCELED" "CANCELLED"} markers))
              (is (= 'contains? (first (first (nth query 4)))))
              (is (= '?marker (last (first (nth query 4)))))
              (is (= (quote [[?block :block/page ?page] [?page :block/uuid ?uuid]]) (subvec query 5)))
              (->> (vals (:blocks @state))
                   (filter #(contains? markers (second (re-find #"^(?:[ \t]*#+[ \t]+)?([A-Z-]+)(?:[ \t]|$)" (:content %)))))
                   (map #(get-in @state [:pages (get-in % [:page :id]) :uuid]))
                   distinct
                   (mapv vector)))
            "logseq.Editor.getPage" (get-in @state [:pages (first args)])
            "logseq.Editor.getBlock" (get-in @state [:blocks (first args)])
            "logseq.Editor.getPageBlocksTree"
            (let [page (first (filter #(= (first args) (:uuid %)) (vals (:pages @state))))]
              (block-tree @state (:id page)))
            "logseq.Editor.createPage"
            (let [page {:id 1 :uuid journal-id :journal? true :journalDay 20260921}]
              (is (= ["2026-09-21" {} {:journal true :redirect false :createFirstBlock false}] args))
              (swap! state assoc-in [:pages 1] page)
              nil) ; File-graph creation can return nil after native title normalization.
            "logseq.Editor.appendBlockInPage"
            (let [[page-id content options] args
                  uuid (get-in options [:properties :id])
                  block {:id 20 :uuid uuid :page {:id (:id (first (filter #(= page-id (:uuid %)) (vals (:pages @state)))))}
                         :content (str content "\nid:: " uuid) :properties {:id uuid}}]
              (is (some #(= page-id (:uuid %)) (vals (:pages @state))))
              (is (false? (:focus options)))
              (swap! state assoc-in [:blocks uuid] block)
              block)
            "logseq.Editor.updateBlock"
            (let [[uuid content] args]
              (swap! state assoc-in [:blocks uuid :content] content)
              nil)
            "logseq.Editor.insertBlock"
            (let [[parent content options] args
                  uuid (str (java.util.UUID/randomUUID))
                  block {:id 21 :uuid uuid :page {:id 1} :content content :fixture-parent parent}]
              (is (= {:sibling false :focus false} options))
              (swap! state assoc-in [:blocks uuid] block)
              block)
            (throw (ex-info "Unexpected Logseq API method" {:method method})))]
      {:status 200 :body (json/generate-string result)})))

(defn runtime []
  {:config app-config :tracker (adapters/build app-config :tracker tracker/registry)})

(defn write-calls [calls] (filter #(contains? writes (first %)) @calls))

(deftest approved-native-create-find-complete-preserves-other-blocks
  (let [state (atom (native-state)) before @state calls (atom []) order (atom [])
        request {:action "create_item" :title "Review a draft" :description "Read the draft carefully."}]
    (with-redefs [http/post (http-stub state calls order)
                  logseq/today (constantly "2026-09-21")
                  shell/run (fn [& _] (throw (ex-info "Unexpected forge call" {})))]
      (let [runtime (runtime)
            preview (agent/preview-data runtime request)]
        (is (= "2026-09-21" (get-in preview [:approvalContext :trackerContext :journalDate])))
        (is (= before @state))
        (is (empty? (write-calls calls)))
        (is (thrown-with-msg? Exception #"Approval does not match"
                              (agent/apply-data! runtime request "lp2_wrong")))
        (is (empty? (write-calls calls)))
        (let [created (:item (agent/apply-data! runtime request (:proposalId preview)))
              uuid (get-in created [:identity :id])
              content (get-in @state [:blocks uuid :content])
              update {:action "update_item" :item uuid :state "completed"}]
          (is (= :logseq (get-in runtime [:tracker :provider])))
          (is (= "/tmp/logseq-graph" (get-in created [:identity :container])))
          (is (= "open" (:state created)))
          (is (str/starts-with? content "TODO Review a draft\nRead the draft carefully."))
          (is (= uuid (get-in @state [:blocks uuid :properties :id])))
          (is (= uuid (get-in (agent/search-data (:tracker runtime) {:kind "item" :query "Review a draft"})
                             [:candidates 0 :identity :id])))
          (is (some #(= uuid (get-in % [:identity :id]))
                    (:items (agent/list-data (:tracker runtime) {:kind "item" :state "open"}))))
          (let [preview (agent/preview-data runtime update)
                blocks (:blocks @state)
                completed (:item (agent/apply-data! runtime update (:proposalId preview)))]
            (is (= uuid (get-in completed [:identity :id])))
            (is (= "completed" (:state completed)))
            (is (= (str/replace-first content #"^TODO" "DONE")
                   (get-in @state [:blocks uuid :content])))
            (is (= (dissoc blocks uuid) (dissoc (:blocks @state) uuid)))
            (is (= (count blocks) (count (:blocks @state)))))
          (is (= (:blocks before) (dissoc (:blocks @state) uuid)))
          (is (= ["logseq.Editor.appendBlockInPage" "logseq.Editor.updateBlock"]
                 (mapv first (write-calls calls)))))))))

(deftest preview-does-not-create-a-missing-journal
  (let [state (atom {:graph (:graph (native-state)) :pages {} :blocks {}})
        calls (atom []) order (atom [])
        request {:action "create_item" :title "Review a draft"}]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime) preview (agent/preview-data runtime request)]
        (is (empty? (:pages @state)))
        (is (empty? (write-calls calls)))
        (agent/apply-data! runtime request (:proposalId preview))
        (is (= ["logseq.Editor.createPage" "logseq.Editor.appendBlockInPage"]
               (mapv first (write-calls calls))))
        (is (= 1 (count (:blocks @state))))))))

(deftest all-pages-search-includes-nested-and-ordinary-page-tasks
  (let [state (atom (assoc-in (native-state) [:blocks task-id :fixture-parent] note-id))
        calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [adapter (:tracker (runtime))
            scope ((:configured-scope adapter))]
        (is (= [task-id] (mapv :display-id ((:list-items adapter) (constantly true) 1))))
        (is (= task-id (:display-id ((:resolve-item adapter) task-id))))
        (is (= task-id (:display-id ((:resolve-item adapter)
                                    (domain/identity-key (domain/contained-identity
                                                          :logseq :tracker-item (:id scope) task-id))))))
        (is (= other-id (:display-id ((:resolve-item adapter) other-id))))
        (is (= #{task-id other-id} (set (map :display-id ((:list-items adapter) (constantly true) 10)))))
        (is (nil? ((:resolve-item adapter) "Missing task")))
        (is (empty? (write-calls calls)))))))

(deftest creation-and-comments-use-native-child-blocks
  (let [state (atom (native-state)) calls (atom []) order (atom [])
        request {:action "create_item" :title "Review a draft" :comment "Original request\nwith details"}]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime) preview (agent/preview-data runtime request)
            item (:item (agent/apply-data! runtime request (:proposalId preview)))
            uuid (get-in item [:identity :id])]
        (is (= ["logseq.Editor.appendBlockInPage" "logseq.Editor.insertBlock"]
               (mapv first (write-calls calls))))
        (is (= (:comment request)
               (:content (first (filter #(= uuid (:fixture-parent %)) (vals (:blocks @state)))))))
        (is (= "open" (:state item)))
        (is (str/starts-with? (get-in @state [:blocks uuid :content]) "TODO Review a draft"))))))

(deftest task-looking-comments-stay-comments-through-preview-and-apply
  (doseq [action ["comment_item" "create_item"]
          [body expected] [["TODO Comment task" "> TODO Comment task"]
                           ["DONE Comment task" "> DONE Comment task"]
                           ["TODO" "> TODO"] ["DONE" "> DONE"]
                           ["TODO\tComment task\n\nDONE detail\n" "> TODO\tComment task\n> \n> DONE detail\n> "]
                           ["DONE Comment task\r\nTODO detail\r\n" "> DONE Comment task\r\n> TODO detail\r\n> "]
                           ["NOW comment" "> NOW comment"] ["LATER" "> LATER"]
                           ["DOING comment" "> DOING comment"] ["WAITING" "> WAITING"]
                           ["WAIT comment" "> WAIT comment"] ["CANCELED" "> CANCELED"]
                           ["CANCELLED comment" "> CANCELLED comment"] ["IN-PROGRESS" "> IN-PROGRESS"]
                           ["STARTED comment" "> STARTED comment"]
                           ["# NOW comment" "> # NOW comment"] ["## LATER" "> ## LATER"]
                           ["### TODO comment" "> ### TODO comment"] ["# DOING" "> # DOING"]
                           ["## DONE comment" "> ## DONE comment"] ["# WAITING" "> # WAITING"]
                           ["# WAIT comment" "> # WAIT comment"] ["# CANCELED" "> # CANCELED"]
                           ["# CANCELLED comment" "> # CANCELLED comment"]
                           ["# IN-PROGRESS" "> # IN-PROGRESS"]
                           ["# STARTED" "> # STARTED"]
                           [" \tTODO comment" ">  \tTODO comment"]
                           [" \t## DONE comment" ">  \t## DONE comment"]
                           ["TODO     \r" "> TODO     \r> "]
                           ["NOW comment\rDONE detail" "> NOW comment\r> DONE detail"]
                           [(str "TODO " (apply str (repeat 16384 " ")) "\r")
                            (str "> TODO " (apply str (repeat 16384 " ")) "\r> ")]
                           ["Ordinary comment\r\nTODO detail" "Ordinary comment\r\nTODO detail"]
                           ["TODOish comment" "TODOish comment"] ["DONE: comment" "DONE: comment"]
                           ["NOWHERE comment" "NOWHERE comment"] ["WAITING-room" "WAITING-room"]
                           ["TODO\u00a0comment" "TODO\u00a0comment"]
                           ["#TODO comment" "#TODO comment"] ["## Note TODO" "## Note TODO"]
                           ["todo comment" "todo comment"]
                           ["> TODO comment\n> DONE detail" "> TODO comment\n> DONE detail"]]]
    (testing (str action " " (pr-str body))
      (let [state (atom (-> (native-state)
                            (assoc-in [:blocks task-id :fixture-parent] note-id)
                            (assoc-in [:blocks other-id :page :id] 1)
                            (assoc-in [:blocks other-id :fixture-parent] task-id)
                            (assoc-in [:blocks other-id :content] "DONE Nested task")))
            before @state calls (atom []) order (atom [])
            request (if (= action "comment_item")
                      {:action action :item task-id :body body}
                      {:action action :title "Created task" :comment body})]
        (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
          (let [runtime (runtime) adapter (:tracker runtime)
                preview (agent/preview-data runtime request)]
            (is (= body (get-in preview [:comment :body])))
            (is (= before @state))
            (is (empty? (write-calls calls)))
            (let [result (agent/apply-data! runtime request (:proposalId preview))
                  parent (get-in result [:item :identity :id])
                  added (apply dissoc (:blocks @state) (keys (:blocks before)))
                  comment (first (filter :fixture-parent (vals added)))
                  item-ids (cond-> #{task-id other-id} (= action "create_item") (conj parent))]
              (is (= expected (:content comment)))
              ;; Independent native first-token oracle: neutral reads only recognize TODO/DONE.
              (is (not (contains? #{"NOW" "LATER" "TODO" "DOING" "DONE" "WAITING" "WAIT"
                                    "CANCELED" "CANCELLED" "IN-PROGRESS" "STARTED"}
                                  (first (str/split (str/replace-first (str/trim (:content comment))
                                                                     #"^#+\s+" "") #"\s+" 2)))))
              (is (= parent (:fixture-parent comment)))
              (is (= (if (= action "create_item") 2 1) (count added)))
              (is (= (:blocks before) (apply dissoc (:blocks @state) (keys added))))
              (is (= item-ids (set (map #(get-in % [:identity :id])
                                       (:items (agent/list-data adapter {:kind "item"}))))))
              (is (= item-ids (set (map :display-id ((:search-parent-items adapter))))))
              (is (not-any? #(= (:uuid comment) (get-in % [:identity :id]))
                            (:candidates (agent/search-data adapter {:kind "item" :query (:uuid comment)}))))
              (is (nil? ((:resolve-item adapter) (:uuid comment))))
              (is (= "open" (:state ((:resolve-item adapter) task-id))))
              (is (= "completed" (:state ((:resolve-item adapter) other-id)))))))))))

(deftest unsupported-work-item-concepts-fail-preview-without-writes
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime)]
        (doseq [fields [{:priority "high"} {:dueAt "2026-09-30T12:00:00Z"}
                        {:availableAt nil} {:blockedBy []}
                        {:project "Work"} {:labels ["follow-up"]}]]
          (is (thrown? Exception (agent/preview-data runtime
                                                   (merge {:action "create_item" :title "Review a draft"} fields)))))
        (is (thrown? Exception (core/preview runtime {:action :create-new :parent-ref task-id})))
        (is (empty? (write-calls calls)))))))

(deftest changed-date-block-and-graph-reject-approval
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime)
            create {:action "create_item" :title "Review a draft"}
            creation (agent/preview-data runtime create)
            update {:action "update_item" :item task-id :state "completed"}
            approval (:proposalId (agent/preview-data runtime update))]
        (with-redefs [logseq/today (constantly "2026-09-22")]
          (is (thrown-with-msg? Exception #"journal date changed"
                                (agent/apply-data! runtime create (:proposalId creation))))
          (is (thrown-with-msg? Exception #"Approval does not match"
                                (agent/apply-data! (ttt.logseq-test/runtime) create (:proposalId creation)))))
        (swap! state update-in [:blocks task-id :content] str "\nA user edit")
        (is (thrown-with-msg? Exception #"Approval does not match"
                              (agent/apply-data! runtime update approval)))
        (swap! state assoc-in [:graph :path] "/tmp/other-graph")
        (is (thrown-with-msg? Exception #"configured Logseq graph"
                              (agent/apply-data! runtime create (:proposalId creation))))
        (is (empty? (write-calls calls)))))))

(deftest foreign-graph-and-non-task-references-cannot-be-mutated
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime)]
        (is (thrown-with-msg? Exception #"not found"
                              (agent/preview-data runtime {:action "update_item" :item note-id :state "completed"})))
        (is (thrown-with-msg? Exception #"outside the configured"
                              (agent/preview-data runtime
                                                  {:action "update_item"
                                                   :item (str "logseq:tracker-item:/tmp/other#" task-id)
                                                   :state "completed"})))
        (is (empty? (write-calls calls)))))))

(deftest provider-settings-validate-local-transport-and-secret-metadata
  (is (= {:tracker {:graph "/tmp/logseq-graph" :token "test-token" :base-url "http://localhost:12315"}}
         (config/env-overrides app-config {"LOGSEQ_GRAPH" "/tmp/logseq-graph"
                                          "LOGSEQ_TOKEN" "test-token"
                                          "LOGSEQ_BASE_URL" "http://localhost:12315"})))
  (is (true? (:secret? (second (get config/provider-settings [:tracker :logseq])))))
  (is (nil? (logseq/assert-ready! app-config)))
  (doseq [url ["https://example.com" "http://example.com" "http://localhost/path"
               "http://user@localhost" "http://localhost?x=1" "http://localhost#fragment"]]
    (is (thrown? Exception (logseq/assert-ready! (assoc-in app-config [:tracker :base-url] url)))))
  (doseq [field [:graph :token]]
    (is (thrown? Exception (logseq/assert-ready! (update app-config :tracker dissoc field)))))
  (is (thrown? Exception (logseq/assert-ready! (assoc-in app-config [:tracker :graph] 1)))))

(deftest malformed-task-and-api-failures-do-not-become-empty-success
  (is (thrown-with-msg? Exception #"valid block UUID"
                        (logseq/normalize-block (domain/scope-identity :logseq "/tmp/logseq-graph")
                                                {:uuid "bad" :content "TODO Review"})))
  (with-redefs [http/post (fn [& _] {:status 401 :body "{\"error\":\"Unauthorized\"}"})]
    (let [failure (try (logseq/assert-graph! app-config)
                       (catch Exception error (agent/failure "preview" error)))]
      (is (= "remote-api-error" (get-in failure [:error :code])))
      (is (= "logseq" (get-in failure [:error :provider])))
      (is (= 401 (get-in failure [:error :status]))))))

(deftest registry-linking-updates-native-block-before-forge
  (let [state (atom (native-state)) before @state calls (atom []) order (atom []) payload (atom nil)]
    (with-redefs [http/post (http-stub state calls order)
                  logseq/today (constantly "2026-09-21")
                  shell/run (integration/command-stub order payload)]
      (let [runtime (adapters/runtime app-config forge/registry tracker/registry)
            proposal (core/preview runtime {:action :link-existing :item-ref task-id})]
        (is (empty? @order))
        (core/apply! runtime proposal)
        (is (= [:tracker :forge] @order))
        (is (str/includes? (get-in @state [:blocks task-id :content]) "https://github.com/org/repo/pull/7"))
        (is (= (get-in before [:blocks task-id :properties]) (get-in @state [:blocks task-id :properties])))
        (is (= (dissoc (:blocks before) task-id) (dissoc (:blocks @state) task-id)))
        (is (str/starts-with? (:title (:payload @payload)) (str "[" task-id "]")))))))

(deftest list-stops-before-reading-unneeded-journals
  (let [old-pages (into {} (map (fn [i]
                                 [i {:id i :uuid (format "%08x-0000-4000-8000-%012x" i i)
                                     :journal? true :journalDay (+ 20250100 i)}])
                               (range 2 30)))
        state (atom (update (native-state) :pages merge old-pages))
        calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [adapter (:tracker (runtime))]
        (is (= [task-id] (mapv :display-id ((:list-items adapter) (constantly true) 1))))
        (is (= [["logseq.Editor.getPageBlocksTree" [journal-id]]]
               (filter #(= "logseq.Editor.getPageBlocksTree" (first %)) @calls)))
        (is (nil? ((:resolve-item adapter) "Review: document:notes#task")))))))

(deftest repeated-completion-is-a-native-no-op
  (let [state (atom (update-in (native-state) [:blocks task-id :content]
                               #(str/replace-first % #"^TODO" "DONE")))
        before @state calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime) request {:action "update_item" :item task-id :state "completed"}
            preview (agent/preview-data runtime request)]
        (agent/apply-data! runtime request (:proposalId preview))
        (is (= before @state))
        (is (empty? (write-calls calls)))))))

(deftest malformed-api-tokens-fail-before-header-construction
  (doseq [token ["test token" "test\ntoken" "test\u0000token"]]
    (is (thrown-with-msg? Exception #"whitespace or control characters"
                          (logseq/assert-ready! (assoc-in app-config [:tracker :token] token))))))

(deftest authenticated-client-does-not-follow-redirects
  (is (= "NEVER" (str (.followRedirects (:client logseq/api-client))))))

(deftest interrupted-creation-reports-native-uuid-without-retrying
  (let [state (atom (native-state)) before @state calls (atom []) order (atom [])
        send (http-stub state calls order)
        request {:action "create_item" :title "Review a draft"}]
    (with-redefs [logseq/today (constantly "2026-09-21")
                  http/post (fn [url options]
                              (let [result (send url options)]
                                (when (= "logseq.Editor.appendBlockInPage"
                                         (:method (json/parse-string (:body options) true)))
                                  (throw (ex-info "Connection closed after native creation." {})))
                                result))]
      (let [runtime (runtime)
            approval (:proposalId (agent/preview-data runtime request))
            error (try (agent/apply-data! runtime request approval)
                       (catch Exception error error))
            uuid (first (remove (set (keys (:blocks before))) (keys (:blocks @state))))]
        (is (= :logseq-create-outcome-unknown (:code (ex-data error))))
        (is (str/includes? (.getMessage error) uuid))
        (is (str/includes? (.getMessage error) "before retrying create_item"))
        (is (= uuid (get-in @state [:blocks uuid :properties :id])))
        (is (= (:blocks before) (dissoc (:blocks @state) uuid)))
        (is (= 1 (count (write-calls calls))))))))

(deftest adapter-rejects-edits-after-preview-before-native-writes
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [adapter (:tracker (runtime))
            item ((:resolve-item adapter) task-id)]
        (swap! state update-in [:blocks task-id :content] str "\nA user edit")
        (let [before @state]
          (is (thrown-with-msg? Exception #"Logseq block changed"
                                ((:update-item! adapter) item {:state "completed"})))
          (is (thrown-with-msg? Exception #"Logseq block changed"
                                ((:comment-item! adapter) item "Follow up.")))
          (is (= before @state))
          (is (empty? (write-calls calls))))))))

(deftest native-markers-and-heading-tasks-support-approved-content-edits
  (doseq [[marker neutral] {"TODO" "open" "LATER" "open" "NOW" "active" "DOING" "active"
                           "STARTED" "active" "IN-PROGRESS" "active" "WAIT" "waiting" "WAITING" "waiting"
                           "DONE" "completed" "CANCELED" "canceled" "CANCELLED" "canceled"}
          prefix ["" "## " "  "]]
    (let [state (atom (assoc-in (native-state) [:blocks other-id :content]
                               (str prefix marker " Original title\nOriginal body\ncustom:: keep\nid:: " other-id)))
          calls (atom []) order (atom [])]
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (runtime) item ((get-in rt [:tracker :resolve-item]) other-id)
              req {:action "update_item" :item other-id :title "Edited title" :description "Edited body\n"}
              approval (:proposalId (agent/preview-data rt req))]
          (is (= neutral (:state item)))
          (is (= "Original body" (:description item)))
          (is (empty? (write-calls calls)))
          (is (thrown? Exception (agent/apply-data! rt (assoc req :title "Unapproved") approval)))
          (let [updated (:item (agent/apply-data! rt req approval))
                raw (get-in @state [:blocks other-id :content])]
            (is (= "Edited title" (:title updated)))
            (is (= "Edited body\n" (:description updated)))
            (is (= neutral (:state updated)))
            (is (str/starts-with? raw (str prefix marker " Edited title\ncustom:: keep\nid:: " other-id "\nEdited body\n")))
            (is (str/includes? raw "custom:: keep"))
            (is (str/includes? raw (str "id:: " other-id)))
            (is (= 1 (count (write-calls calls))))))))))

(deftest state-only-edits-preserve-native-separators-and-property-placement
  (doseq [prefix ["" "## "] sep [" " "\t"] newline ["\n" "\r\n"]]
    (let [raw (str prefix "TODO" sep "Title" newline "id:: " other-id newline "Body ")
          state (atom (assoc-in (native-state) [:blocks other-id :content] raw))
          calls (atom []) order (atom [])]
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (runtime) req {:action "update_item" :item other-id :state "active"}]
          (agent/apply-data! rt req (:proposalId (agent/preview-data rt req)))
          (is (= (str prefix "DOING" (subs raw (+ (count prefix) 4)))
                 (get-in @state [:blocks other-id :content]))))))))

(deftest selected-page-creation-supports-all-neutral-states
  (doseq [neutral (keys logseq/native-states)]
    (let [state (atom (native-state)) calls (atom []) order (atom [])]
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (runtime)
              req {:action "create_item" :project "Ordinary page" :title "Page task" :state neutral}
              p (agent/preview-data rt req)
              item (:item (agent/apply-data! rt req (:proposalId p)))
              uuid (get-in item [:identity :id])]
          (is (nil? (get-in p [:approvalContext :trackerContext])))
          (is (= neutral (:state item)))
          (is (= 2 (get-in @state [:blocks uuid :page :id])))
          (is (= ["logseq.Editor.appendBlockInPage"] (mapv first (write-calls calls)))))))))

(deftest native-property-and-multiline-title-injection-fails-before-write
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order)]
      (doseq [fields [{:title "First\nSecond"} {:title " "}
                      {:description (str "id:: " other-id)} {:description "custom:: changed"}]]
        (is (thrown? Exception (agent/preview-data (runtime)
                              (merge {:action "update_item" :item task-id} fields)))))
      (is (empty? (write-calls calls))))))

(deftest classic-title-and-body-edits-preserve-priority-and-indented-properties
  (doseq [cookie ["[#A]" "[#B]" "[#C]"] indentation ["  " "\t"]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])
          property (str indentation "custom:: keep")
          raw (str "TODO " cookie " Original\nOriginal body\n" property "\nid:: " task-id)]
      (swap! state assoc-in [:blocks task-id :content] raw)
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (runtime) item ((get-in rt [:tracker :resolve-item]) task-id)]
          (is (= "Original" (:title item)))
          (is (= "Original body" (:description item)))
          (doseq [fields [{:title "Renamed"} {:description "Replacement body"} {:state "active"}]]
            (let [req (merge {:action "update_item" :item task-id} fields)]
              (agent/apply-data! rt req (:proposalId (agent/preview-data rt req)))))
          (is (= (str "DOING " cookie " Renamed\n" property "\nid:: " task-id "\nReplacement body")
                 (get-in @state [:blocks task-id :content])))
          (let [before (count (write-calls calls))]
            (doseq [fields [{:description (str indentation "id:: " other-id)}
                            {:description (str indentation "custom:: changed")}]]
              (is (thrown? Exception (agent/preview-data rt (merge {:action "update_item" :item task-id} fields)))))
            (is (= before (count (write-calls calls))))))))))

(deftest fenced-property-looking-source-remains-body-text
  (doseq [fence ["```" "~~~"]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])
          body (str fence "cpp\nstd::vector<int> xs;\n  a::b\n" fence)]
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (runtime)
              req {:action "update_item" :item task-id :description body}
              item (:item (agent/apply-data! rt req (:proposalId (agent/preview-data rt req))))]
          (is (= body (:description item)))
          (let [rename {:action "update_item" :item task-id :title "Renamed"}]
            (agent/apply-data! rt rename (:proposalId (agent/preview-data rt rename))))
          (is (= body (:description ((get-in rt [:tracker :resolve-item]) task-id)))))))))

(deftest leading-priority-cookies-are-not-title-only-mutations
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order)]
      (let [rt (runtime) before @state]
        (doseq [action ["create_item" "update_item"]
                title ["[#A] Renamed" "[#B] Renamed" "  [#C] Renamed" "\t[#A] Renamed"]]
          (let [request (cond-> {:action action :title title}
                          (= action "update_item") (assoc :item task-id))
                error (try (agent/preview-data rt request) (catch Exception e e))]
            (is (= :unsupported-work-item-value (:code (ex-data error))))))
        (is (empty? (write-calls calls)))
        (is (= before @state))))))

(deftest unclosed-body-fences-cannot-consume-existing-native-properties
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (swap! state update-in [:blocks task-id :content] str "\ncustom:: keep")
    (with-redefs [http/post (http-stub state calls order)]
      (let [rt (runtime)]
        (doseq [body ["```text\nunfinished" "fixed" "~~~text\nunfinished" "fixed again"]]
          (let [request {:action "update_item" :item task-id :description body}
                item (:item (agent/apply-data! rt request (:proposalId (agent/preview-data rt request))))
                raw (get-in @state [:blocks task-id :content])]
            (is (= body (:description item)))
            (is (str/includes? raw (str "id:: " task-id)))
            (is (str/includes? raw "custom:: keep"))))))))

(deftest missing-classic-approved-page-rejects-uuid-title-substitution
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order)]
      (let [a (:tracker (runtime)) project ((:resolve-project a) "Ordinary page")
            ref (:ref project)]
        (swap! state update :pages dissoc 2)
        (swap! state assoc-in [:pages 3] {:id 3 :uuid "ffffffff-ffff-4fff-8fff-ffffffffffff" :name (:id ref) :journal? false})
        (let [error (try ((:create-item! a) {:project project} {:title "Approved only"})
                         (catch Exception e e))]
          (is (= :stale-proposal (:code (ex-data error))))
          (is (empty? (write-calls calls))))))))

(deftest derived-creation-properties-are-quoted-without-changing-fenced-source
  (let [body "Estimate:: 3\n```cpp\nstd::vector<int> xs;\n```\n  Note:: x"
        intent (logseq/validate-work-item-intent! :create-new {} {:title "Derived title" :description body})]
    (is (= "> Estimate:: 3\n```cpp\nstd::vector<int> xs;\n```\n>   Note:: x" (:description intent)))
    (is (= intent (logseq/validate-work-item-intent! :create-item {} intent)))))

(deftest registry-derived-create-new-quotes-body-through-preview-and-exact-apply
  (let [state (atom (native-state)) calls (atom []) order (atom []) payload (atom nil)
        body "Estimate:: 3\n```cpp\nstd::vector<int> xs;\n```\n  Note:: x\n:PROPERTIES:\n:custom: source\n:END:"
        quoted "> Estimate:: 3\n```cpp\nstd::vector<int> xs;\n```\n>   Note:: x\n> :PROPERTIES:\n> :custom: source\n> :END:"
        command (integration/command-stub order payload)
        source-body (atom body)]
    (with-redefs [http/post (http-stub state calls order)
                  logseq/today (constantly "2026-09-21")
                  shell/run (fn [& args]
                              (let [out (apply command args)]
                                (if (= "view" (nth args 2 nil))
                                  (json/generate-string (assoc (json/parse-string out true) :body @source-body))
                                  out)))]
      (let [rt (adapters/runtime app-config forge/registry tracker/registry)
            request {:action "create_new"}
            preview (agent/preview-data rt request)]
        (is (str/includes? (get-in preview [:trackerIntent :description]) quoted))
        (reset! source-body (str body "\nchanged after approval"))
        (is (thrown-with-msg? Exception #"Approval does not match"
                              (agent/apply-data! rt request (:proposalId preview))))
        (is (empty? (write-calls calls)))
        (reset! source-body body)
        (let [item (:item (agent/apply-data! rt request (:proposalId preview)))]
          (is (str/includes? (:description item) quoted))
          (is (= [:tracker :forge] @order)))
        (reset! order [])
        (let [p (agent/preview-data rt {:action "link_existing" :item task-id})]
          (is (not (str/includes? (get-in p [:trackerIntent :description]) "Estimate:: 3")))
          (agent/apply-data! rt {:action "link_existing" :item task-id} (:proposalId p))
          (is (= [:tracker :forge] @order)))))))

(deftest task-discovery-does-not-fetch-ten-thousand-unrelated-page-trees
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (swap! state update :pages into
           (for [i (range 3 10003)]
             [i {:id i :uuid (str (java.util.UUID/randomUUID)) :name (str "Empty page " i) :journal? false}]))
    (doseq [[i marker] (map-indexed vector ["TODO" "LATER" "NOW" "DOING" "STARTED" "IN-PROGRESS" "WAIT" "WAITING" "DONE" "CANCELED" "CANCELLED"])]
      (let [uuid (str (java.util.UUID/randomUUID))]
        (swap! state assoc-in [:blocks uuid] {:id (+ 50 i) :uuid uuid :page {:id 2}
                                             :content (str "## " marker " Indexed task " i)})))
    (with-redefs [http/post (http-stub state calls order)]
      (let [a (:tracker (runtime)) results ((:search-parent-items a))]
        (is (= 13 (count results)))
        (is (= 1 (count (filter #(= "logseq.DB.datascriptQuery" (first %)) @calls))))
        (is (= 2 (count (filter #(= "logseq.Editor.getPageBlocksTree" (first %)) @calls))))
        (is (<= (count @calls) 10))
        (is (empty? (write-calls calls)))))))

(deftest native-fence-boundaries-control-property-safety
  (doseq [indent ["" "   " "    " "\t"]
          opener ["```cpp" "~~~text"]
          closer ["```" "~~~" "```tail"]]
    (let [body (str indent opener "\nFoo:: literal source\n" indent closer)]
      (is (= [body []] (logseq/split-description body)))
      (is (= body (:description (logseq/validate-work-item-intent! :update-item {} {:description body}))))))
  (doseq [body ["```cpp\nFoo:: native property" "~~~text\nFoo:: native property"
                "```\n~~~\nFoo:: native property" "```\n```tail\nFoo:: native property"]]
    (is (= ["Foo:: native property"] (second (logseq/split-description body))))
    (is (thrown-with-msg? Exception #"Edit native Logseq properties"
                          (logseq/validate-work-item-intent! :update-item {} {:description body})))
    (is (str/includes? (:description (logseq/validate-work-item-intent! :create-new {} {:description body}))
                       "> Foo:: native property"))))

(deftest registry-preserves-native-drawers-and-literal-line-endings
  (doseq [newline ["\n" "\r\n"]
          body [(str "Line one" newline "Line two" newline)
                (str ":PROPERTIES:" newline ":custom: keep" newline ":END:" newline "Body" newline)]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])]
      (swap! state assoc-in [:blocks task-id :content] (str "TODO Existing" newline body))
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (adapters/runtime app-config forge/registry tracker/registry)
              request {:action "update_item" :item task-id :title "Renamed"}
              proposal (agent/preview-data rt request)]
          (agent/apply-data! rt request (:proposalId proposal))
          (is (= (str "TODO Renamed" newline body) (get-in @state [:blocks task-id :content])))
          (let [request {:action "update_item" :item task-id :description (str "Replacement" newline)}
                proposal (agent/preview-data rt request)
                result (agent/apply-data! rt request (:proposalId proposal))]
            (is (= (str "Replacement" newline) (get-in result [:item :description])))
            (when (str/starts-with? body ":PROPERTIES:")
              (is (str/includes? (get-in @state [:blocks task-id :content])
                                 (str ":PROPERTIES:" newline ":custom: keep" newline ":END:"))))))))))

(deftest registry-rejects-explicit-drawers-before-approval
  (let [state (atom (native-state)) calls (atom []) order (atom [])
        drawer ":PROPERTIES:\r\n:custom: changed\r\n:END:"]
    (with-redefs [http/post (http-stub state calls order)]
      (let [rt (adapters/runtime app-config forge/registry tracker/registry)]
        (is (thrown-with-msg? Exception #"Edit native Logseq properties"
                              (agent/preview-data rt {:action "update_item" :item task-id :description drawer})))
        (is (empty? (write-calls calls)))))
    (is (= ["" [":PROPERTIES:" ":custom: changed" ":END:"]] (logseq/split-description drawer)))
    (is (= "> :PROPERTIES:\r\n> :custom: changed\r\n> :END:"
           (:description (logseq/validate-work-item-intent! :create-new {} {:description drawer}))))
    (is (= [(str "```\n" drawer "\n~~~") []]
           (logseq/split-description (str "```\n" drawer "\n~~~"))))))

(deftest native-drawers-preserve-source-order-and-closing-text
  (doseq [start [":PROPERTIES:" ":properties:" "    :PROPERTIES:" "\t:PROPERTIES:"]
          ending [":END:" ":end:" ":END: trailing" ":END:tail"]]
    (let [body (str start "\n:custom: keep\n" ending)
          tail (subs ending 5)]
      (is (= [tail [start ":custom: keep" (subs ending 0 5)]] (logseq/split-description body)))
      (is (thrown-with-msg? Exception #"Edit native Logseq properties"
                            (logseq/validate-work-item-intent! :update-item {} {:description body})))))
  (doseq [body [":PROPERTIES:\nHello\n:END:" ":PROPERTIES:\nFoo:: literal\n:END:"
                ":PROPERTIES:\n:custom: keep" ":LOGBOOK:\nFoo:: literal\n:END:"]]
    (is (= [body []] (logseq/split-description body))))
  (let [body ":PROPERTIES:\n```\n:END:\nFoo:: native\n~~~"]
    (is (= ["Foo:: native"] (second (logseq/split-description body))))
    (is (thrown-with-msg? Exception #"Edit native Logseq properties"
                          (logseq/validate-work-item-intent! :update-item {} {:description body}))))
  (is (= "One\r\nTwo\nThree\rFour\r\n"
         (first (logseq/split-description "One\r\nTwo\nThree\rFour\r\n")))))

(deftest registry-title-only-keeps-interleaved-and-appended-native-properties
  (doseq [body ["First\ncustom:: keep\r\nSecond\n"
                (str "Body\r\nid:: " task-id)
                "First\n:PROPERTIES:\n:custom: keep\n:END:tail\r\nSecond\n"]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])]
      (swap! state assoc-in [:blocks task-id :content] (str "TODO Existing\r\n" body))
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (adapters/runtime app-config forge/registry tracker/registry)
              request {:action "update_item" :item task-id :title "Renamed"}
              p (agent/preview-data rt request)]
          (agent/apply-data! rt request (:proposalId p))
          (is (= (str "TODO Renamed\r\n" body) (get-in @state [:blocks task-id :content]))))))))

(deftest direct-adapter-title-only-keeps-omitted-body
  (let [state (atom (native-state)) calls (atom []) order (atom [])
        body (str "Body\r\nid:: " task-id)]
    (swap! state assoc-in [:blocks task-id :content] (str "TODO Existing\r\n" body))
    (with-redefs [http/post (http-stub state calls order)]
      (let [a (:tracker (adapters/runtime app-config forge/registry tracker/registry))]
        ((:update-item! a) ((:resolve-item a) task-id) {:title "Direct"})
        (is (= (str "TODO Direct\r\n" body) (get-in @state [:blocks task-id :content])))))))

(deftest registry-explicit-same-neutral-state-keeps-every-native-marker
  (doseq [[marker state-name] [["TODO" "open"] ["LATER" "open"] ["NOW" "active"] ["DOING" "active"]
                               ["STARTED" "active"] ["IN-PROGRESS" "active"] ["WAIT" "waiting"] ["WAITING" "waiting"]
                               ["DONE" "completed"] ["CANCELED" "canceled"] ["CANCELLED" "canceled"]]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])
          raw (str marker " [#A] Native\r\nBody\ncustom:: keep")]
      (swap! state assoc-in [:blocks task-id :content] raw)
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (adapters/runtime app-config forge/registry tracker/registry)
              req {:action "update_item" :item task-id :state state-name}
              p (agent/preview-data rt req)]
          (agent/apply-data! rt req (:proposalId p))
          (is (= raw (get-in @state [:blocks task-id :content])))
          (is (empty? (write-calls calls))))))))

(deftest literal-double-colons-and-quote-continuations-remain-body
  (doseq [body ["std::vector<int> xs;" "MyClass::CONST = 5" "Note::see below" "Foo::: bar" "Foo::\tbar"
                "> quote\nFoo:: bar" ">quote\r\nFoo:: bar" "> quote\n>\nFoo:: bar"]]
    (is (= [body []] (logseq/split-description body)))
    (is (= body (:description (logseq/validate-work-item-intent! :create-new {} {:description body}))))
    (let [state (atom (native-state)) calls (atom []) order (atom [])]
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (adapters/runtime app-config forge/registry tracker/registry)
              req {:action "update_item" :item task-id :description body}
              p (agent/preview-data rt req)
              result (agent/apply-data! rt req (:proposalId p))]
          (is (= body (get-in result [:item :description])))
          (is (str/includes? (get-in @state [:blocks task-id :content]) body))))))
  (doseq [body ["Foo:: bar" "Foo::" "> quote\n\nFoo:: bar" "> quote\n# Heading\nFoo:: bar"
                "> quote\nid:: native-id" "> quote\n- sibling\nFoo:: bar"]]
    (is (seq (second (logseq/split-description body))))
    (is (thrown-with-msg? Exception #"Edit native Logseq properties"
                          (logseq/validate-work-item-intent! :update-item {} {:description body})))))

(deftest registry-empty-description-clears-body-but-keeps-native-metadata
  (let [state (atom (native-state)) calls (atom []) order (atom [])
        metadata (str "custom:: keep\r\n:PROPERTIES:\r\n:id: " task-id "\r\n:END:")]
    (swap! state assoc-in [:blocks task-id :content] (str "LATER [#B] Native\r\nOld body\r\n" metadata))
    (with-redefs [http/post (http-stub state calls order)]
      (let [rt (adapters/runtime app-config forge/registry tracker/registry)
            request {:action "update_item" :item task-id :description ""}
            p (agent/preview-data rt request)
            result (agent/apply-data! rt request (:proposalId p))]
        (is (= "" (get-in result [:item :description])))
        (is (= (str "LATER [#B] Native\r\n" metadata) (get-in @state [:blocks task-id :content])))
        (is (not (str/includes? (get-in @state [:blocks task-id :content]) "Old body")))
        (is (= 1 (count (write-calls calls))))))))

(deftest registry-rejects-leading-native-title-separators-before-approval
  (doseq [title [" Leading" "\tLeading"]
          request [{:action "update_item" :item task-id :title title}
                   {:action "create_item" :title title :project "Ordinary page"}]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])]
      (with-redefs [http/post (http-stub state calls order)]
        (is (thrown-with-msg? Exception #"Logseq task titles"
                              (agent/preview-data (adapters/runtime app-config forge/registry tracker/registry) request)))
        (is (empty? (write-calls calls)))))))

(deftest native-deep-heading-tasks-survive-discovery-and-updates
  (doseq [depth [7 12]]
    (let [state (atom (native-state)) calls (atom []) order (atom [])
          prefix (str (apply str (repeat depth "#")) " ")]
      (swap! state assoc-in [:blocks task-id :content] (str prefix "TODO Deep"))
      (with-redefs [http/post (http-stub state calls order)]
        (let [rt (adapters/runtime app-config forge/registry tracker/registry)
              items ((:list-items (:tracker rt)) #(= task-id (:display-id %)) 10)
              request {:action "update_item" :item task-id :state "completed"}
              p (agent/preview-data rt request)]
          (is (= 1 (count items)))
          (agent/apply-data! rt request (:proposalId p))
          (is (= (str prefix "DONE Deep") (get-in @state [:blocks task-id :content]))))))))
