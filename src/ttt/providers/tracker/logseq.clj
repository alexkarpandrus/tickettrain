(ns ttt.providers.tracker.logseq
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]))

(def default-base-url "http://127.0.0.1:12315")
(def native-states {"open" "TODO" "completed" "DONE"})

(def api-client (http/client {:follow-redirects :never}))

(defn today [] (str (java.time.LocalDate/now)))

(defn base-url [app-config]
  (str/replace (or (get-in app-config [:tracker :base-url]) default-base-url) #"/+$" ""))

(defn assert-ready! [app-config]
  (let [settings (:tracker app-config)]
    (config/assert-settings! :logseq settings [:graph :token])
    (when-not (every? string? (map settings [:graph :token]))
      (throw (ex-info "Logseq graph and token must be strings."
                      {:code :provider-config-invalid :provider :logseq})))
    (when (re-find #"[\s\p{Cc}]" (:token settings))
      (throw (ex-info "Logseq token must not contain whitespace or control characters."
                      {:code :provider-config-invalid :provider :logseq})))
    (let [uri (try (java.net.URI. (base-url app-config)) (catch Exception _ nil))]
      (when-not (and uri (= "http" (.getScheme uri))
                     (#{"localhost" "127.0.0.1" "[::1]"} (.getHost uri))
                     (nil? (.getUserInfo uri)) (nil? (.getQuery uri))
                     (nil? (.getFragment uri)) (str/blank? (.getPath uri)))
        (throw (ex-info "Logseq base-url must be a loopback HTTP URL without a path, credentials, query, or fragment."
                        {:code :provider-config-invalid :provider :logseq}))))))

(defn request! [app-config method args]
  (remote/request!
   :logseq
   #(http/post (str (base-url app-config) "/api")
               {:headers {"Authorization" (str "Bearer " (get-in app-config [:tracker :token]))
                          "Content-Type" "application/json"}
                :body (json/generate-string {:method method :args args})
                :client api-client
                :throw false})))

(defn assert-graph! [app-config]
  (let [graph (request! app-config "logseq.App.getCurrentGraph" [])
        expected (get-in app-config [:tracker :graph])]
    (when-not (= expected (:path graph))
      (throw (ex-info "Open the configured Logseq graph before running this command."
                      {:code :tracker-scope-mismatch :provider :logseq})))
    (domain/scope-identity :logseq expected)))

(defn api! [app-config method & args]
  ;; The desktop API targets the open graph, not a graph supplied with each request.
  (assert-graph! app-config)
  (request! app-config method (vec args)))

(defn invalid-response! [message]
  (throw (ex-info message {:code :invalid-logseq-response :provider :logseq})))

(defn block-uuid? [value]
  (and (string? value)
       (boolean (re-matches #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" value))))

(defn journal? [page] (true? (:journal? page)))

(def task-content-pattern #"(?s)^(TODO|DONE)(?:[ \t]+([^\r\n]*))?(?:\r?\n(.*))?$")

(defn normalize-block [scope block]
  (when-let [[_ marker title description]
             (when (string? (:content block))
               (re-matches task-content-pattern (:content block)))]
    (when-not (block-uuid? (:uuid block))
      (invalid-response! "Logseq returned a task without a valid block UUID."))
    {:ref (domain/contained-identity :logseq :tracker-item (:id scope) (:uuid block))
     :display-id (:uuid block)
     :title (or title "")
     :description (or description "")
     :state (if (= marker "TODO") "open" "completed")
     :scopes [scope]
     :labels []
     :logseq/content (:content block)}))

(defn journal-pages [app-config]
  (let [pages (api! app-config "logseq.Editor.getAllPages")]
    (when-not (sequential? pages)
      (invalid-response! "Logseq did not return its pages."))
    (->> pages (filter journal?) (sort-by :journalDay >))))

(defn page-items [app-config scope page]
  (let [blocks (api! app-config "logseq.Editor.getPageBlocksTree" (:uuid page))]
    (when-not (sequential? blocks)
      (invalid-response! "Logseq did not return the journal's block tree."))
    (->> blocks
         (mapcat #(tree-seq (comp seq :children) :children %))
         (keep #(normalize-block scope %)))))

(defn list-items [app-config scope matches? limit]
  ;; ponytail: scan journals newest first; use a native query if graph size makes this slow.
  (into [] (comp (mapcat #(page-items app-config scope %))
                 (filter matches?) (take limit))
        (journal-pages app-config)))

(defn resolve-item [app-config scope reference]
  (let [identity (when (str/starts-with? reference "logseq:tracker-item:")
                   (domain/key-identity reference))
        id (if identity (:id identity) reference)]
    (when (and identity
               (or (not= :logseq (:provider identity))
                   (not= :tracker-item (:kind identity))
                   (not= (:id scope) (:container identity))))
      (throw (ex-info "The item reference is outside the configured Logseq graph."
                      {:code :tracker-scope-mismatch :provider :logseq})))
    (when (block-uuid? id)
      (when-let [block (api! app-config "logseq.Editor.getBlock" id)]
        (when-let [item (normalize-block scope block)]
          (when (journal? (api! app-config "logseq.Editor.getPage" (get-in block [:page :id])))
            item))))))

(defn unsupported! [concept]
  (throw (ex-info (str "The Logseq journal tracker does not support " (name concept) ".")
                  {:code :unsupported-capability :provider :logseq :capability concept})))

(defn validate-work-item-intent! [_action _target intent]
  (when (and (contains? intent :state) (not (contains? native-states (:state intent))))
    (throw (ex-info "Logseq journal tasks support only open and completed states."
                    {:code :unsupported-work-item-value :provider :logseq
                     :field :state :value (:state intent)})))
  intent)

(defn current-journal! [app-config journal-date]
  (let [day (Long/parseLong (str/replace journal-date "-" ""))
        find-page #(first (filter (fn [page] (= day (:journalDay page)))
                                  (journal-pages app-config)))
        page (or (find-page)
                 (do
                   ;; Logseq formats ISO journal titles; discover the native page after creation.
                   (api! app-config "logseq.Editor.createPage" journal-date {}
                         {:journal true :redirect false :createFirstBlock false})
                   (find-page)))]
    (when-not (block-uuid? (:uuid page))
      (invalid-response! "Logseq did not return today's journal page."))
    page))
(defn create-item! [app-config scope journal-date context intent]
  (when-not (= journal-date (today))
    (throw (ex-info "The journal date changed. Preview again before applying."
                    {:code :stale-proposal :provider :logseq})))
  (when (:parent context) (unsupported! :parents))
  (when (:project context) (unsupported! :projects))
  (when (seq (:labels intent)) (unsupported! :labels))
  (validate-work-item-intent! :create-item context intent)
  (let [page (current-journal! app-config journal-date)
        uuid (str (java.util.UUID/randomUUID))
        content (str (get native-states (:state intent "open")) " " (:title intent)
                     (when-not (str/blank? (:description intent))
                       (str "\n" (:description intent))))]
    (try
      ;; id is Logseq's native persisted block property, not a ttt-owned identifier.
      (let [block (api! app-config "logseq.Editor.appendBlockInPage"
                        (:uuid page) content {:properties {:id uuid} :focus false})
            _ (when-not (= uuid (:uuid block))
                (invalid-response! "Logseq did not return the created block UUID."))]
        (or (resolve-item app-config scope uuid)
            (invalid-response! "Logseq did not return the created journal task.")))
      (catch Exception error
        (throw (ex-info (str "Inspect Logseq block " uuid
                            " in today's journal before retrying create_item; creation may have succeeded.")
                        {:code :logseq-create-outcome-unknown :provider :logseq}
                        error))))))

(defn current-item! [app-config scope item]
  (let [current (or (resolve-item app-config scope (get-in item [:ref :id]))
                    (throw (ex-info "The Logseq journal task no longer exists."
                                    {:code :tracker-item-not-found :provider :logseq})))]
    (when-not (= (:logseq/content item) (:logseq/content current))
      (throw (ex-info "The Logseq block changed. Preview again before applying."
                      {:code :stale-proposal :provider :logseq})))
    current))

(defn update-item! [app-config scope item intent]
  (validate-work-item-intent! :update-item item intent)
  (when (seq (:labels intent)) (unsupported! :labels))
  (let [current (current-item! app-config scope item)
        content (:logseq/content current)
        description (:description intent)
        content (if (and (contains? intent :description)
                         (not= description (:description current)))
                  (str (first (str/split-lines content))
                       (when-not (str/blank? description) (str "\n" description)))
                  content)
        content (if (contains? intent :state)
                  (str/replace-first content #"^(TODO|DONE)" (get native-states (:state intent)))
                  content)]
    (when-not (= content (:logseq/content current))
      (api! app-config "logseq.Editor.updateBlock" (get-in item [:ref :id]) content))
    (or (resolve-item app-config scope (get-in item [:ref :id]))
        (invalid-response! "Inspect the Logseq block before retrying; the updated task could not be read."))))

(defn native-task-comment? [body]
  ;; Native markers are broader than the TODO/DONE states exposed by this tracker.
  (boolean (re-find #"^\s*+(?:#++\s++)?(?:NOW|LATER|TODO|DOING|DONE|WAITING|WAIT|CANCELED|CANCELLED|IN-PROGRESS)(?=\s|$)"
                    body)))

(defn comment-item! [app-config scope item body]
  (current-item! app-config scope item)
  (let [body (if (native-task-comment? body)
               (str "> " (str/replace body #"\r\n|\r|\n" "$0> "))
               body)
        block (api! app-config "logseq.Editor.insertBlock"
                    (get-in item [:ref :id]) body {:sibling false :focus false})]
    (when-not (block-uuid? (:uuid block))
      (invalid-response! "Inspect the Logseq block before retrying; its comment could not be confirmed.")))
  nil)

(defn setup [app-config]
  (assert-ready! app-config)
  (assert-graph! app-config)
  (println "Logseq tracker: configured graph is open"))

(defn neutral-adapter [app-config]
  (let [scope (domain/scope-identity :logseq (get-in app-config [:tracker :graph]))
        journal-date (today)]
    {:provider :logseq
     :capabilities (get adapters/required-capabilities :tracker)
     :item-capabilities #{:item-lifecycle}
     :configured-scope #(assert-graph! app-config)
     :approval-context (fn [request]
                         (when (#{:create-item :create-new} (:action request))
                           {:journalDate journal-date}))
     :list-items #(list-items app-config scope %1 %2)
     :search-parent-items #(list-items app-config scope (constantly true) Long/MAX_VALUE)
     :resolve-item #(resolve-item app-config scope %)
     :resolve-parent-item (fn [_] (unsupported! :parents))
     :search-projects (constantly [])
     :resolve-project (fn [_] (unsupported! :projects))
     :search-labels (constantly [])
     :resolve-labels (fn [references _]
                       (when (seq references) (unsupported! :labels))
                       [])
     :validate-work-item-intent! validate-work-item-intent!
     :create-item! #(create-item! app-config scope journal-date %1 %2)
     :update-item! #(update-item! app-config scope %1 %2)
     :comment-item! #(comment-item! app-config scope %1 %2)}))
