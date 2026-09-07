(ns ttt.cli.agent
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.config :as config]
            [ttt.core :as core]
            [ttt.domain :as domain]
            [ttt.providers.forge :as forge]
            [ttt.text.fuzzy :as fuzzy]
            [ttt.providers.tracker :as tracker]))

(def schema-version 2)
(def max-request-bytes 65536)
(def option-spec {:config {} :kind {} :query {} :limit {:coerce :long} :project {}
                  :scope-item {} :request {} :request-file {} :approve {}})

(defn success [command data] {:schemaVersion schema-version :ok true :command command :data data})
(defn failure [command ex] {:schemaVersion schema-version :ok false :command command
                            :error {:code (name (or (:code (ex-data ex)) :agent-error))
                                    :message (.getMessage ex)}})
(defn require-option [options option]
  (or (get options option) (throw (ex-info (str "--" (name option) " is required.") {:code :invalid-request}))))
(defn parse-options [args] (:opts (cli/parse-args args {:spec option-spec})))

(defn request-file-content [path]
  (let [file (java.io.File. path)]
    (when-not (.isFile file) (throw (ex-info "--request-file must reference a regular file." {:code :invalid-request})))
    (when (> (.length file) max-request-bytes) (throw (ex-info "--request-file exceeds 64 KiB." {:code :invalid-request})))
    (slurp file)))

(defn parse-request [options]
  (let [inline-request (:request options) request-file (:request-file options)]
    (when (= (boolean inline-request) (boolean request-file))
      (throw (ex-info "Provide exactly one of --request or --request-file." {:code :invalid-request})))
    (let [request-json (if request-file (request-file-content request-file) inline-request)]
      (when-not (string? request-json) (throw (ex-info "--request requires a JSON value." {:code :invalid-request})))
      (when (> (count (.getBytes request-json "UTF-8")) max-request-bytes)
        (throw (ex-info "The request exceeds 64 KiB." {:code :invalid-request})))
      (try (json/parse-string request-json true)
           (catch Exception ex (throw (ex-info "The request must contain valid JSON." {:code :invalid-request} ex)))))))

(defn wire-identity [entity] (some-> entity :ref domain/identity-data))
(defn wire-entity [entity]
  (when entity
    (cond-> {:identity (wire-identity entity) :displayId (:display-id entity)}
      (:title entity) (assoc :title (:title entity))
      (:url entity) (assoc :url (:url entity))
      (:description entity) (assoc :description (:description entity))
      (:state entity) (assoc :state (:state entity))
      (:parent entity) (assoc :parent (wire-entity (:parent entity)))
      (:project entity) (assoc :project (wire-entity (:project entity)))
      (:score entity) (assoc :score (:score entity)))))
(defn wire-source [{:keys [branch repository change-request]}]
  {:branch branch :repository (wire-entity repository) :changeRequest (wire-entity change-request)})
(defn wire-context [{:keys [parent project]}] {:parent (wire-entity parent) :project (wire-entity project)})
(defn wire-action [action] (case action :link-existing "link_existing" :create-new "create_new"))
(defn wire-request [request]
  (cond-> {:action (wire-action (:action request)) :labels (vec (:labels request))}
    (:item-ref request) (assoc :item (:item-ref request))
    (:parent-ref request) (assoc :parent (:parent-ref request))
    (:project-ref request) (assoc :project (:project-ref request))
    (:title request) (assoc :title (:title request))))
(defn wire-intent [intent]
  (cond-> {:description (:description intent) :labels (mapv wire-entity (:labels intent))}
    (:title intent) (assoc :title (:title intent))))
(defn proposal->wire [proposal]
  {:proposalId (:proposal-id proposal) :source (wire-source (:source proposal))
   :action (wire-action (:action proposal)) :request (wire-request (:request proposal))
   :item (wire-entity (:item proposal)) :context (wire-context (:context proposal))
   :labels (mapv wire-entity (:labels proposal)) :trackerIntent (wire-intent (:tracker-intent proposal))
   :changeRequestUpdate (:change-request-update proposal)})
(defn inspect-data [runtime] (wire-source (core/inspect runtime)))
(defn excerpt [value] (let [text (some-> value str str/trim)] (when-not (str/blank? text) (subs text 0 (min 500 (count text))))))
(defn entity-candidate [entity]
  (cond-> (wire-entity entity) (excerpt (:description entity)) (assoc :descriptionExcerpt (excerpt (:description entity)))))
(defn resolve-project! [tracker-adapter project-ref]
  (or ((:resolve-project tracker-adapter) project-ref)
      (throw (ex-info (str "Tracker project not found: " project-ref) {:code :project-not-found}))))
(defn bounded-limit [value]
  (let [limit (or value 5)]
    (when-not (<= 1 limit 10) (throw (ex-info "--limit must be between 1 and 10." {:code :invalid-request}))) limit))
(defn search-items [tracker-adapter query options limit]
  (let [scope ((:configured-scope tracker-adapter))
        project (when-let [project-ref (:project options)]
                  (let [resolved (resolve-project! tracker-adapter project-ref)]
                    (when-not (domain/entity-in-scope? resolved scope)
                      (throw (ex-info "The project is outside the configured tracker scope." {:code :tracker-scope-mismatch :project (:ref resolved) :expected-scope scope}))) resolved))]
    (->> ((:search-parent-items tracker-adapter)) (filter #(domain/entity-in-scope? % scope))
         (filter #(or (nil? project) (domain/in-project? % project))) (fuzzy/rank-issues query) (take limit) (mapv entity-candidate))))
(defn search-projects [tracker-adapter query limit]
  (let [scope ((:configured-scope tracker-adapter))]
    (->> ((:search-projects tracker-adapter)) (filter #(domain/entity-in-scope? % scope))
         (fuzzy/rank-issues query) (take limit) (mapv entity-candidate))))
(defn search-labels [tracker-adapter query options limit]
  (let [scope-item-ref (:scope-item options) scope-item (when scope-item-ref ((:resolve-item tracker-adapter) scope-item-ref)) scope ((:configured-scope tracker-adapter)) label-scopes (if-let [scopes (seq (:scopes scope-item))] scopes [scope])]
    (when (and scope-item-ref (nil? scope-item)) (throw (ex-info (str "Tracker item not found: " scope-item-ref) {:code :tracker-item-not-found :item-ref scope-item-ref})))
    (when (and scope-item (not (domain/entity-in-scope? scope-item scope)))
      (throw (ex-info "The scope item is outside the configured tracker scope." {:code :tracker-scope-mismatch :item (:ref scope-item) :expected-scope scope})))
    (->> ((:search-labels tracker-adapter))
         (filter #(or (empty? (:scopes %)) (some (fn [s] (domain/entity-in-scope? % s)) label-scopes)))
         (map #(assoc % :title (:display-id %))) (fuzzy/rank-issues query) (take limit) (mapv entity-candidate))))
(defn search-data [tracker-adapter options]
  (let [kind (require-option options :kind) query (require-option options :query) limit (bounded-limit (:limit options))]
    (when (str/blank? query) (throw (ex-info "--query must not be blank." {:code :invalid-request})))
    {:kind kind :query query :candidates (case kind
      "item" (search-items tracker-adapter query options limit)
      "project" (search-projects tracker-adapter query limit)
      "label" (search-labels tracker-adapter query options limit)
      (throw (ex-info (str "Unsupported search kind: " kind) {:code :invalid-request})))}))
(defn invalid-request! [message] (throw (ex-info message {:code :invalid-request})))
(defn assert-request-shape! [request]
  (doseq [field [:item :parent :project :title] :when (contains? request field)]
    (when-not (string? (get request field)) (invalid-request! (str (name field) " must be a string."))))
  (when (and (contains? request :labels) (not (and (sequential? (:labels request)) (every? string? (:labels request)))))
    (invalid-request! "labels must be a collection of strings.")) request)
(defn validate-request! [request]
  (assert-request-shape! request)
  (case (:action request)
    "link_existing" (do
                      (when-not (seq (:item request)) (invalid-request! "link_existing requires item."))
                      (when (or (:issue request) (:parent request) (:project request) (:title request)) (invalid-request! "link_existing accepts only item and labels.")))
    "create_new" (do
                    (when-not (or (seq (:parent request)) (seq (:project request))) (invalid-request! "create_new requires parent or project."))
                    (when (or (:item request) (:issue request)) (invalid-request! "create_new accepts parent, project, title, and labels.")))
    (invalid-request! (str "Unsupported action: " (:action request))))
  request)

(defn core-request [request]
  (cond-> {:action (case (:action request) "link_existing" :link-existing "create_new" :create-new) :labels (vec (or (:labels request) []))}
    (:item request) (assoc :item-ref (:item request)) (:parent request) (assoc :parent-ref (:parent request))
    (:project request) (assoc :project-ref (:project request)) (:title request) (assoc :title (:title request))))
(defn canonicalize [value]
  (cond (map? value) (into (sorted-map-by #(compare (str %1) (str %2))) (map (fn [[key item]] [key (canonicalize item)])) value)
        (sequential? value) (mapv canonicalize value) :else value))
(defn sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (json/generate-string (canonicalize value)) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))
(defn proposal-id [proposal] (str "lp2_" (subs (sha256 proposal) 0 24)))
(defn preview-proposal [runtime request]
  (validate-request! request)
  (let [source (core/inspect runtime)]
    (when-not (:change-request source)
      (throw (ex-info "Schema-v2 preview and apply require an open change request. Use the interactive workflow to create the initial change request."
                      {:code :change-request-not-found})))
    (let [proposal (core/preview runtime source (core-request request))]
      (assoc proposal :proposal-id (proposal-id proposal)))))
(defn preview-data [runtime request] (proposal->wire (preview-proposal runtime request)))
(defn apply-data! [runtime request approval]
  (let [proposal (preview-proposal runtime request)]
    (when-not (= approval (:proposal-id proposal)) (throw (ex-info "Approval does not match the current proposal. Preview again before applying." {:code :stale-proposal})))
    (let [result (core/apply! runtime proposal)]
      {:proposalId (:proposal-id proposal) :item (wire-entity (:item result)) :changeRequest (wire-entity (:change-request result)) :changeRequestUpdate (:change-request-update result)})))
(defn runtime [options]
  (let [app-config (config/load-config (or (:config options) config/default-config-path))] (adapters/runtime app-config forge/registry tracker/registry)))
(defn execute-command [command args]
  (if (= "version" command)
    {:name "ttt" :agentApiVersion schema-version :capabilities ["inspect-current-change-request" "search-items" "search-projects" "search-labels" "link-existing" "create-new" "approval-gated-apply"]}
    (let [options (parse-options args) runtime (runtime options) tracker-adapter (:tracker runtime)]
      (case command "inspect" (inspect-data runtime) "search" (search-data tracker-adapter options)
        "preview" (preview-data runtime (parse-request options)) "apply" (apply-data! runtime (parse-request options) (require-option options :approve))
        (throw (ex-info (str "Unknown agent command: " command) {:code :invalid-command}))))))

(defn run [args]
  (let [command (or (first args) "unknown")]
    (try {:exit 0 :envelope (success command (execute-command command (rest args)))}
         (catch Exception ex {:exit 2 :envelope (failure command ex)}))))
