(ns ttt.providers.tracker.logseq
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]))

(def default-base-url "http://127.0.0.1:12315")
(def native-states {"open" "TODO" "active" "DOING" "waiting" "WAITING"
                    "completed" "DONE" "canceled" "CANCELED"})
(def marker-states {"TODO" "open" "LATER" "open" "NOW" "active" "DOING" "active"
                    "STARTED" "active" "IN-PROGRESS" "active" "WAIT" "waiting"
                    "WAITING" "waiting" "DONE" "completed" "CANCELED" "canceled"
                    "CANCELLED" "canceled"})

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

(def task-content-pattern #"(?s)^([ \t]*+(?:#++[ \t]++)?)(NOW|LATER|TODO|DOING|DONE|WAITING|WAIT|CANCELED|CANCELLED|IN-PROGRESS|STARTED)(?:[ \t]++([^\r\n]*+))?(?:\r?\n(.*))?$")
(def property-line-pattern #"^[ \t]*+[^\s:]+::(?: [^\r\n]*)?$")

(defn property-line? [line] (boolean (re-matches property-line-pattern line)))

(defn split-description
  ([description] (split-description description false))
  ([description quote-properties?]
   ;; Native source and drawers consume complete spans in source order.
   (let [text (or description "")
         lines (str/split text #"\r\n|\r|\n" -1)
         separators (vec (re-seq #"\r\n|\r|\n" text))
         fences (into (sorted-set) (keep-indexed #(when (re-find #"^[ \t]*+(?:`{3}|~{3})" %2) %1) lines))
         ends (into (sorted-set) (keep-indexed #(when (re-find #"(?i)^[ \t]*+:END:" %2) %1) lines))
         ;; mldoc quotes consume lazy lines until a blank or native new-block boundary.
         quote-stops (into (sorted-set)
                           (keep-indexed #(when (or (re-matches #"[ \t]*" %2)
                                                    (re-find #"^[ \t]*+(?:>[ \t]*+)?(?:- |# |id:: |-$|#$)" %2)) %1) lines))
         [protected drawers tails]
         (loop [index 0 protected #{} drawers #{} tails {}]
           (if (= index (count lines))
             [protected drawers tails]
             (let [drawer (re-matches #"(?i)^[ \t]*+:([^\s:]+):[ \t]*$" (nth lines index))
                   fence? (contains? fences index)
                   quote? (and (re-find #"^[ \t]*+>" (nth lines index))
                               (not (contains? quote-stops index)))
                   end (cond fence? (first (subseq fences > index))
                             quote? (dec (or (first (subseq quote-stops > index)) (count lines)))
                             drawer (first (subseq ends > index)))]
               (if end
                 (let [span (range index (inc end))
                       properties? (and (not (or fence? quote?)) (= "properties" (str/lower-case (second drawer)))
                                        (< (inc index) end)
                                        (every? #(re-matches #"^[ \t]*+:[^\s:]+:[^\r\n]*$" %)
                                                (subvec lines (inc index) end)))
                       [_ closing tail] (when properties? (re-matches #"(?i)^([ \t]*+:END:)(.*)$" (nth lines end)))]
                   (recur (inc end)
                          (if properties? protected (into protected span))
                          (if properties? (into drawers span) drawers)
                          (if properties? (assoc tails end [closing tail]) tails)))
                 (recur (inc index) protected drawers tails)))))
         [body properties]
         (reduce (fn [[body properties] [index line]]
                   (if (and (not (contains? protected index))
                            (or (contains? drawers index) (property-line? line)))
                     (let [[closing tail] (get tails index)]
                       [(cond quote-properties? (conj body [index (str "> " line)])
                              (seq tail) (conj body [index tail])
                              :else body)
                        (conj properties (or closing line))])
                     [(conj body [index line]) properties]))
                 [[] []] (map-indexed vector lines))]
     [(apply str (map-indexed (fn [position [index line]]
                               (str line (when (< position (dec (count body))) (get separators index))))
                             body))
      properties])))

(defn normalize-page [scope page]
  (when-not (block-uuid? (:uuid page))
    (invalid-response! "Logseq returned a page without a valid UUID."))
  {:ref (domain/contained-identity :logseq :project (:id scope) (:uuid page))
   :display-id (:uuid page)
   :title (or (:originalName page) (:name page) (:uuid page))
   :scopes [scope]})

(defn normalize-block [scope block]
  (when-let [[_ prefix marker title description]
             (when (string? (:content block))
               (re-matches task-content-pattern (:content block)))]
    (when-not (block-uuid? (:uuid block))
      (invalid-response! "Logseq returned a task without a valid block UUID."))
    (let [[_ priority title-without-priority] (re-matches #"(?s)^(\[#[A-Z]\][ \t]*+)(.*)$" (or title ""))]
      {:ref (domain/contained-identity :logseq :tracker-item (:id scope) (:uuid block))
       :display-id (:uuid block)
       :title (or title-without-priority title "")
       :logseq/priority-prefix priority
       :description (first (split-description description))
     :state (get marker-states marker)
     :scopes [scope]
     :labels []
     :logseq/prefix prefix
     :logseq/marker marker
     :logseq/content (:content block)})))

(defn journal-pages [app-config]
  (let [pages (api! app-config "logseq.Editor.getAllPages")]
    (when-not (sequential? pages)
      (invalid-response! "Logseq did not return its pages."))
    (->> pages (filter journal?) (sort-by :journalDay >))))

(defn task-pages [app-config]
  (let [pages (api! app-config "logseq.Editor.getAllPages")]
    (when-not (sequential? pages) (invalid-response! "Logseq did not return its pages."))
    (concat (sort-by :journalDay > (filter journal? pages))
            (sort-by :name (remove journal? pages)))))


(def task-page-query
  (str "[:find ?uuid :where [?block :block/marker ?marker] "
       "[(contains? " (pr-str (set (keys marker-states))) " ?marker)] "
       "[?block :block/page ?page] [?page :block/uuid ?uuid]]"))

(defn task-bearing-pages [app-config]
  (let [rows (api! app-config "logseq.DB.datascriptQuery" task-page-query)]
    (when-not (and (sequential? rows)
                   (every? #(and (sequential? %) (= 1 (count %)) (block-uuid? (first %))) rows))
      (invalid-response! "Logseq did not return task page UUIDs."))
    (let [ids (set (map first rows))]
      (filter #(contains? ids (:uuid %)) (task-pages app-config)))))
(defn projects [app-config scope]
  (mapv #(normalize-page scope %) (task-pages app-config)))

(defn resolve-project [app-config scope reference]
  (let [identity (when (str/starts-with? reference "logseq:project:")
                   (domain/key-identity reference))
        id (if identity (:id identity) reference)]
    (when (and identity (not= (:id scope) (:container identity)))
      (throw (ex-info "The page reference is outside the configured Logseq graph."
                      {:code :tracker-scope-mismatch :provider :logseq})))
    (let [matches (filter #(or (= id (:display-id %))
                              (= (str/lower-case id) (str/lower-case (:title %))))
                          (projects app-config scope))]
      (when (> (count matches) 1)
        (throw (ex-info "The Logseq page reference is ambiguous. Use its UUID."
                        {:code :ambiguous-project :provider :logseq})))
      (first matches))))
(defn page-items [app-config scope page]
  (let [blocks (api! app-config "logseq.Editor.getPageBlocksTree" (:uuid page))]
    (when-not (sequential? blocks)
      (invalid-response! "Logseq did not return the page's block tree."))
    (->> blocks
         (mapcat #(tree-seq (comp seq :children) :children %))
         (keep #(when-let [item (normalize-block scope %)]
                  (assoc item :project (normalize-page scope page)))))))

(defn list-items [app-config scope matches? limit]
  ;; Native marker discovery avoids fetching trees for unrelated note pages.
  (into [] (comp (mapcat #(page-items app-config scope %))
                 (filter matches?) (take limit))
        (task-bearing-pages app-config)))

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
        (when-let [page (api! app-config "logseq.Editor.getPage" (get-in block [:page :id]))]
          (when-let [item (normalize-block scope block)]
            (assoc item :project (normalize-page scope page))))))))

(defn unsupported! [concept]
  (throw (ex-info (str "The Logseq tracker does not support " (name concept) ".")
                  {:code :unsupported-capability :provider :logseq :capability concept})))

(defn validate-work-item-intent! [action _target intent]
  (when (and (contains? intent :state) (not (contains? native-states (:state intent))))
    (throw (ex-info "Logseq does not support the requested state."
                    {:code :unsupported-work-item-value :provider :logseq
                     :field :state :value (:state intent)})))
  (when (and (contains? intent :title)
             (or (str/blank? (:title intent)) (re-find #"[\r\n]" (:title intent))
                 (re-find #"^(?:[ \t]|\[#[A-Z]\])" (:title intent))))
    (throw (ex-info "Logseq task titles must be nonblank single-line text without leading whitespace or a native priority cookie."
                    {:code :unsupported-work-item-value :provider :logseq :field :title})))
  (when (and (not= :create-new action) (seq (second (split-description (:description intent)))))
    (throw (ex-info "Edit native Logseq properties in Logseq, not through the task body."
                    {:code :unsupported-work-item-value :provider :logseq :field :description})))
  (if (= :create-new action)
    (assoc intent :description (first (split-description (:description intent) true)))
    intent))

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
  (when (and (nil? (:project context)) (not= journal-date (today)))
    (throw (ex-info "The journal date changed. Preview again before applying."
                    {:code :stale-proposal :provider :logseq})))
  (when (:parent context) (unsupported! :parents))
  (when (seq (:labels intent)) (unsupported! :labels))
  (validate-work-item-intent! :create-item context intent)
  (let [page (if-let [project (:project context)]
               (or (first (filter #(= (get-in project [:ref :id]) (:uuid %)) (task-pages app-config)))
                   (throw (ex-info "The Logseq destination page no longer exists."
                                   {:code :stale-proposal :provider :logseq})))
               (current-journal! app-config journal-date))
        uuid (str (java.util.UUID/randomUUID))
        content (str (get native-states (:state intent "open")) " " (:title intent)
                     (when (seq (:description intent))
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
                            " in the approved page before retrying create_item; creation may have succeeded.")
                        {:code :logseq-create-outcome-unknown :provider :logseq}
                        error))))))

(defn current-item! [app-config scope item]
  (let [current (or (resolve-item app-config scope (get-in item [:ref :id]))
                    (throw (ex-info "The Logseq task no longer exists."
                                    {:code :tracker-item-not-found :provider :logseq})))]
    (when-not (= (:logseq/content item) (:logseq/content current))
      (throw (ex-info "The Logseq block changed. Preview again before applying."
                      {:code :stale-proposal :provider :logseq})))
    current))

(defn update-item! [app-config scope item intent]
  (validate-work-item-intent! :update-item item intent)
  (when (seq (:labels intent)) (unsupported! :labels))
  (let [current (current-item! app-config scope item)
        content-changed? (some (fn [field] (and (contains? intent field)
                                                (not= (get intent field) (get current field))))
                               [:title :description])
        marker (if (and (contains? intent :state) (not= (:state intent) (:state current)))
                 (get native-states (:state intent)) (:logseq/marker current))
        content (if content-changed?
                  (str (:logseq/prefix current) marker " " (:logseq/priority-prefix current) (get intent :title (:title current))
                       (if (and (contains? intent :description)
                                (not= (:description intent) (:description current)))
                         (let [newline (or (re-find #"\r\n|\r|\n" (:logseq/content current)) "\n")
                               raw-body (second (re-find #"(?s)^[^\r\n]*+(?:\r\n|\r|\n)(.*)$" (:logseq/content current)))
                               properties (second (split-description raw-body))
                               description (:description intent)]
                           ;; Keep metadata outside source opened by replacement text.
                           (str (when (seq properties) (str newline (str/join newline properties)))
                                (when (seq description) (str newline description))))
                         ;; Title-only edits must not rebuild an omitted native body.
                         (or (re-find #"(?s)[\r\n].*" (:logseq/content current)) "")))
                  (let [start (count (:logseq/prefix current))
                        end (+ start (count (:logseq/marker current)))
                        raw (:logseq/content current)]
                    (str (subs raw 0 start) marker (subs raw end))))]
    (when-not (= content (:logseq/content current))
      (api! app-config "logseq.Editor.updateBlock" (get-in item [:ref :id]) content))
    (or (resolve-item app-config scope (get-in item [:ref :id]))
        (invalid-response! "Inspect the Logseq block before retrying; the updated task could not be read."))))

(defn native-task-comment? [body]
  ;; Quote a task-looking prefix so a native comment cannot become another task.
  (boolean (re-find #"^\s*+(?:#++\s++)?(?:NOW|LATER|TODO|DOING|DONE|WAITING|WAIT|CANCELED|CANCELLED|IN-PROGRESS|STARTED)(?=\s|$)"
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
     :item-capabilities #{:item-titles :item-descriptions :item-lifecycle}
     :configured-scope #(assert-graph! app-config)
     :approval-context (fn [request]
                         (when (and (#{:create-item :create-new} (:action request))
                                    (nil? (:project-ref request)))
                           {:journalDate journal-date}))
     :list-items #(list-items app-config scope %1 %2)
     :search-parent-items #(list-items app-config scope (constantly true) Long/MAX_VALUE)
     :resolve-item #(resolve-item app-config scope %)
     :resolve-parent-item (fn [_] (unsupported! :parents))
     :search-projects #(projects app-config scope)
     :resolve-project #(resolve-project app-config scope %)
     :search-labels (constantly [])
     :resolve-labels (fn [references _]
                       (when (seq references) (unsupported! :labels))
                       [])
     :validate-work-item-intent! validate-work-item-intent!
     :create-item! #(create-item! app-config scope journal-date %1 %2)
     :update-item! #(update-item! app-config scope %1 %2)
     :comment-item! #(comment-item! app-config scope %1 %2)}))
