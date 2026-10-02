(ns ttt.logseq-test
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
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
                  block {:id 20 :uuid uuid :page {:id 1}
                         :content (str content "\nid:: " uuid) :properties {:id uuid}}]
              (is (= journal-id page-id))
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

(deftest journal-search-includes-nested-tasks-and-excludes-ordinary-pages
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
        (is (nil? ((:resolve-item adapter) other-id)))
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

(deftest unsupported-work-item-concepts-fail-preview-without-writes
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime)]
        (doseq [fields [{:priority "high"} {:dueAt "2026-09-30T12:00:00Z"}
                        {:availableAt nil} {:blockedBy []} {:state "waiting"}
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

(deftest foreign-graph-and-non-journal-references-cannot-be-mutated
  (let [state (atom (native-state)) calls (atom []) order (atom [])]
    (with-redefs [http/post (http-stub state calls order) logseq/today (constantly "2026-09-21")]
      (let [runtime (runtime)]
        (is (thrown-with-msg? Exception #"not found"
                              (agent/preview-data runtime {:action "update_item" :item other-id :state "completed"})))
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
