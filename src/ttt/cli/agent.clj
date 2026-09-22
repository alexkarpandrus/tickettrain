(ns ttt.cli.agent
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.config :as config]
            [ttt.core :as core]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]
            [ttt.platform.shell :as shell]
            [ttt.providers.forge :as forge]
            [ttt.text.fuzzy :as fuzzy]
            [ttt.inference.typesafe :as typesafe]
            [ttt.providers.tracker :as tracker]))

(def schema-version 2)
(def product-version (str/trim (slurp (java.io.File. (or (System/getenv "TTT_HOME") ".") "version.txt"))))
(def max-request-bytes 65536)
(def item-states #{"open" "active" "waiting" "completed" "canceled"})
(def item-priorities #{"none" "low" "medium" "high" "urgent"})
(def work-item-fields [:state :priority :dueAt :availableAt :blockedBy])
(def option-spec {:config {:coerce :string}
                  :profile {:coerce :string}
                  :kind {:coerce :string}
                  :query {:coerce :string}
                  :limit {:coerce :long}
                  :semantic {:coerce :boolean}
                  :project {:coerce :string}
                  :scope-item {:coerce :string}
                  :state {:coerce :string}
                  :label {:coerce :string}
                  :request {:coerce :string}
                  :request-file {:coerce :string}
                  :approve {:coerce :string}})

(defn success [command data] {:schemaVersion schema-version :ok true :command command :data data})
(defn exception-chain [ex] (take-while some? (iterate #(.getCause %) ex)))
(defn first-ex-data [chain pred]
  (some #(let [data (ex-data %)] (when (pred data) data)) chain))
(defn failure [command ex]
  (let [chain (exception-chain ex)
        remote-data (first-ex-data chain #(or (:provider %) (:status %) (:detail %)))
        command-data (first-ex-data chain :command)
        connectivity? (some #(= :github-connectivity (:kind (ex-data %))) chain)
        code (or (some #(some-> % ex-data :code) chain)
                 (when connectivity? :provider-unavailable)
                 (when remote-data :remote-api-error)
                 (when command-data :provider-command-failed)
                 :agent-error)
        provider (or (:provider remote-data)
                     (when (some-> command-data :command (str/starts-with? "gh ")) :github))
        details (or (:detail remote-data)
                    (remote/error-detail (:body remote-data))
                    (shell/first-nonblank-line (:err command-data))
                    (shell/first-nonblank-line (:out command-data)))]
    {:schemaVersion schema-version :ok false :command command
     :error (cond-> {:code (name code) :message (.getMessage ex)}
              provider (assoc :provider (name provider))
              (:status remote-data) (assoc :status (:status remote-data))
              details (assoc :details details))}))
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
(defn wire-blocker [entity]
  (cond-> {:identity (wire-identity entity) :displayId (:display-id entity)}
    (:title entity) (assoc :title (:title entity))))
(defn wire-entity [entity]
  (when entity
    (let [item? (= :tracker-item (get-in entity [:ref :kind]))]
      (cond-> {:identity (wire-identity entity) :displayId (:display-id entity)}
        (:title entity) (assoc :title (:title entity))
        (:url entity) (assoc :url (:url entity))
        (or item? (:description entity)) (assoc :description (:description entity))
        item? (assoc :state (:state entity)
                     :priority (or (:priority entity) "none")
                     :dueAt (:due-at entity)
                     :availableAt (:available-at entity)
                     :blockedBy (mapv wire-blocker (or (:blocked-by entity) []))
                     :project (wire-entity (:project entity))
                     :labels (mapv wire-entity (or (:labels entity) [])))
        (and (not item?) (:state entity)) (assoc :state (:state entity))
        (:parent entity) (assoc :parent (wire-entity (:parent entity)))
        (and (not item?) (:project entity)) (assoc :project (wire-entity (:project entity)))
        (and (not item?) (:labels entity)) (assoc :labels (mapv wire-entity (:labels entity)))
        (:score entity) (assoc :score (:score entity))))))
(defn wire-source [{:keys [branch repository change-request]}]
  {:branch branch :repository (wire-entity repository) :changeRequest (wire-entity change-request)})
(defn wire-context [{:keys [parent project]}] {:parent (wire-entity parent) :project (wire-entity project)})
(defn wire-action [action] (case action :link-existing "link_existing" :create-new "create_new" :create-item "create_item" :update-item "update_item" :create-change-request "create_change_request" :update-change-request "update_change_request" :comment-item "comment_item" :comment-change-request "comment_change_request"))
(defn wire-request [request]
  (cond-> {:action (wire-action (:action request))}
    (contains? request :labels) (assoc :labels (vec (:labels request)))
    (contains? request :add-labels) (assoc :addLabels (vec (:add-labels request)))
    (contains? request :remove-labels) (assoc :removeLabels (vec (:remove-labels request)))
    (:item-ref request) (assoc :item (:item-ref request))
    (:parent-ref request) (assoc :parent (:parent-ref request))
    (:project-ref request) (assoc :project (:project-ref request))
    (:title request) (assoc :title (:title request))
    (contains? request :description) (assoc :description (:description request))
    (contains? request :comment) (assoc :comment (:comment request))
    (contains? request :body) (assoc :body (:body request))
    (contains? request :state) (assoc :state (:state request))
    (contains? request :priority) (assoc :priority (:priority request))
    (contains? request :due-at) (assoc :dueAt (:due-at request))
    (contains? request :available-at) (assoc :availableAt (:available-at request))
    (contains? request :blocked-by) (assoc :blockedBy (vec (:blocked-by request)))))
(defn wire-intent [intent]
  (cond-> {:description (:description intent) :labels (mapv wire-entity (:labels intent))}
    (:title intent) (assoc :title (:title intent))
    (contains? intent :state) (assoc :state (:state intent))
    (contains? intent :priority) (assoc :priority (:priority intent))
    (contains? intent :due-at) (assoc :dueAt (:due-at intent))
    (contains? intent :available-at) (assoc :availableAt (:available-at intent))
    (contains? intent :blocked-by) (assoc :blockedBy (mapv wire-blocker (:blocked-by intent)))))
(defn proposal->wire [proposal]
  (let [base (cond-> {:proposalId (:proposal-id proposal)
                      :action (wire-action (:action proposal))
                      :request (wire-request (:request proposal))}
               (:source proposal) (assoc :source (wire-source (:source proposal)))
               (:profile proposal) (assoc :profile (name (:profile proposal))))]
    (case (:action proposal)
      :create-item
      (assoc base
             :context (wire-context (:context proposal))
             :labels (mapv wire-entity (:labels proposal))
             :trackerIntent (wire-intent (:tracker-intent proposal))
             :approvalContext (:approval-context proposal))

      :update-item
      (assoc base
             :item (wire-entity (:item proposal))
             :trackerIntent (wire-intent (:tracker-intent proposal))
             :labels (mapv wire-entity (:labels proposal))
             :labelChanges {:add (mapv wire-entity (get-in proposal [:label-changes :add]))
                            :remove (mapv wire-entity (get-in proposal [:label-changes :remove]))}
             :comment (:comment proposal)
             :approvalContext (:approval-context proposal))

      :create-change-request
      (assoc base :changeRequestIntent (:change-request-intent proposal))

      :update-change-request
      (assoc base
             :changeRequest (wire-entity (:change-request proposal))
             :changeRequestUpdate (:change-request-update proposal))

      :comment-item
      (assoc base :item (wire-entity (:item proposal)) :comment (:comment proposal)
             :approvalContext (:approval-context proposal))

      :comment-change-request
      (assoc base :changeRequest (wire-entity (:change-request proposal)) :comment (:comment proposal))

      (assoc base
             :item (wire-entity (:item proposal))
             :context (wire-context (:context proposal))
             :labels (mapv wire-entity (:labels proposal))
             :trackerIntent (wire-intent (:tracker-intent proposal))
             :changeRequestUpdate (:change-request-update proposal)
             :approvalContext (:approval-context proposal)))))
(defn inspect-data [runtime] (wire-source (core/inspect runtime)))
(defn- configured-setting-names [settings]
  (->> (dissoc settings :provider)
       (keep (fn [[key value]]
               (when-not (config/missing-setting? value) (name key))))
       sort
       vec))
(defn- config-source [path]
  {:path path :present (.isFile (java.io.File. path))})
(defn status-data [options]
  (let [path (or (:config options) config/default-config-path)
        app-config (config/load-config path (:profile options))]
    (cond->
     {:forge {:provider (name (config/forge-provider app-config))
              :configuredSettings (configured-setting-names (:forge app-config))}
      :tracker {:provider (name (config/tracker-provider app-config))
                :configuredSettings (configured-setting-names (:tracker app-config))}
      :sources {:baseConfig (config-source path)
                :localConfig (config-source config/default-local-config-path)
                :credentialHelper {:configured (boolean (:credential-helper app-config))
                                   :name (:credential-helper app-config)}
                :dotenv (config-source config/default-dotenv-path)
                :processEnvironment {:checked true}}
      :precedence ["baseConfig" "localConfig" "credentialHelper" "dotenv" "processEnvironment"]}
      (:profile app-config) (assoc :profile (name (:profile app-config))))))
(defn excerpt [value] (let [text (some-> value str str/trim)] (when-not (str/blank? text) (subs text 0 (min 500 (count text))))))
(defn entity-candidate [entity]
  (cond-> (wire-entity entity) (excerpt (:description entity)) (assoc :descriptionExcerpt (excerpt (:description entity)))))
(defn resolve-project! [tracker-adapter project-ref]
  (or ((:resolve-project tracker-adapter) project-ref)
      (throw (ex-info (str "Tracker project not found: " project-ref) {:code :project-not-found}))))
(defn bounded-limit [value]
  (let [limit (or value 5)]
    (when-not (<= 1 limit 10) (throw (ex-info "--limit must be between 1 and 10." {:code :invalid-request}))) limit))

(defn bounded-list-limit [value]
  (let [limit (or value 50)]
    (when-not (<= 1 limit 100)
      (throw (ex-info "--limit must be between 1 and 100." {:code :invalid-request})))
    limit))

(defn named-entity? [entity expected]
  (let [expected (some-> expected str/trim str/lower-case)]
    (or (nil? expected)
        (some #(= expected (some-> % str str/trim str/lower-case))
              [(:name entity) (:display-id entity) (:title entity) (get-in entity [:ref :id])]))))


(defn resolve-search-item [tracker-adapter query]
  (try
    ((:resolve-item tracker-adapter) query)
    (catch Exception ex
      (if (= :ambiguous-item (:code (ex-data ex)))
        nil
        (throw ex)))))

(defn list-data [tracker-adapter options]
  (let [kind (require-option options :kind)
        scope ((:configured-scope tracker-adapter))
        state (some-> (:state options) str/lower-case)
        project (:project options)
        label (:label options)
        limit (bounded-list-limit (:limit options))
        matches? (fn [item]
                   (and (domain/entity-in-scope? item scope)
                        (or (nil? state) (= state (:state item)))
                        (named-entity? (:project item) project)
                        (or (nil? label)
                            (some #(named-entity? % label) (:labels item)))))]
    (when-not (= "item" kind)
      (throw (ex-info (str "Unsupported list kind: " kind) {:code :invalid-request})))
    (when (and state (not (contains? item-states state)))
      (throw (ex-info "--state must be open, active, waiting, completed, or canceled."
                      {:code :invalid-request})))
    {:items (mapv wire-entity ((:list-items tracker-adapter) matches? limit))}))
(defn search-items [tracker-adapter query options limit]
  (let [scope ((:configured-scope tracker-adapter))
        exact (some-> (resolve-search-item tracker-adapter query)
                      (as-> item
                          (when (and (domain/entity-in-scope? item scope)
                                     (= (str/lower-case query)
                                        (str/lower-case (str (:display-id item)))))
                            item)))
        project (when-let [project-ref (:project options)]
                  (let [resolved (resolve-project! tracker-adapter project-ref)]
                    (when-not (domain/entity-in-scope? resolved scope)
                      (throw (ex-info "The project is outside the configured tracker scope." {:code :tracker-scope-mismatch :project (:ref resolved) :expected-scope scope}))) resolved))]
    (if (and exact (or (nil? project) (domain/in-project? exact project)))
      [(entity-candidate (assoc exact :score 1.0))]
      (->> ((:search-parent-items tracker-adapter)) (filter #(domain/entity-in-scope? % scope))
           (filter #(or (nil? project) (domain/in-project? % project))) (fuzzy/rank-issues query) (take limit) (mapv entity-candidate)))))
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
(defn exact-candidate?
  [query candidates]
  (and (= 1 (count candidates))
       (= (str/lower-case query)
          (some-> candidates first :displayId str str/lower-case))))

(defn semantic-search
  [query candidates]
  (cond
    (exact-candidate? query candidates)
    {:candidates candidates :ranking {:method "exact"}}

    (empty? candidates)
    {:candidates candidates :ranking {:method "lexical" :fallbackReason "no-candidates"}}

    :else
    (try
      (or (typesafe/rerank query candidates)
          {:candidates candidates :ranking {:method "lexical" :fallbackReason "no-semantic-match"}})
      (catch Exception ex
        {:candidates candidates
         :ranking {:method "lexical"
                   :fallbackReason (or (some-> ex ex-data :code name) "jev-unavailable")}}))))

(defn search-data [tracker-adapter options]
  (let [kind (require-option options :kind)
        query (require-option options :query)
        limit (bounded-limit (:limit options))
        semantic? (:semantic options)
        search-limit (if semantic? typesafe/max-candidates limit)]
    (when (str/blank? query) (throw (ex-info "--query must not be blank." {:code :invalid-request})))
    (let [candidates (case kind
                       "item" (search-items tracker-adapter query options search-limit)
                       "project" (search-projects tracker-adapter query search-limit)
                       "label" (search-labels tracker-adapter query options search-limit)
                       (throw (ex-info (str "Unsupported search kind: " kind) {:code :invalid-request})))
          result (if semantic? (semantic-search query candidates) {:candidates candidates})]
      (assoc result :kind kind :query query :candidates (vec (take limit (:candidates result)))))))
(defn invalid-request! [message] (throw (ex-info message {:code :invalid-request})))

(def action-fields
  {"link_existing" #{:action :item :labels}
   "create_new" #{:action :parent :project :title :labels}
   "create_item" (set/union #{:action :title :description :project :labels} (set work-item-fields))
   "update_item" (set/union #{:action :item :comment :addLabels :removeLabels} (set work-item-fields))
   "create_change_request" #{:action :title :body}
   "update_change_request" #{:action :title :body}
   "comment_item" #{:action :item :body}
   "comment_change_request" #{:action :body}})

(defn valid-instant? [value]
  (try
    (java.time.Instant/parse value)
    true
    (catch Exception _ false)))

(defn assert-request-shape! [request]
  (doseq [field [:item :parent :project :title :body :description :comment] :when (contains? request field)]
    (when-not (string? (get request field)) (invalid-request! (str (name field) " must be a string."))))
  (doseq [field [:labels :addLabels :removeLabels :blockedBy] :when (contains? request field)]
    (when-not (and (sequential? (get request field)) (every? string? (get request field)))
      (invalid-request! (str (name field) " must be a collection of strings."))))
  (when (and (contains? request :state) (not (contains? item-states (:state request))))
    (invalid-request! "state must be open, active, waiting, completed, or canceled."))
  (when (and (contains? request :priority)
             (some? (:priority request))
             (not (contains? item-priorities (:priority request))))
    (invalid-request! "priority must be none, low, medium, high, urgent, or null."))
  (doseq [field [:dueAt :availableAt] :when (contains? request field)]
    (when-not (or (nil? (get request field))
                  (and (string? (get request field)) (valid-instant? (get request field))))
      (invalid-request! (str (name field) " must be an ISO-8601 timestamp or null."))))
  request)

(defn validate-request! [request]
  (let [action (:action request)]
    (when-not (contains? action-fields action)
      (invalid-request! (str "Unsupported action: " action))))
  (assert-request-shape! request)
  (case (:action request)
    "link_existing" (when-not (seq (:item request)) (invalid-request! "link_existing requires item."))
    "create_item" (do
                    (when (str/blank? (:title request)) (invalid-request! "create_item requires title."))
                    (when (and (contains? request :project) (str/blank? (:project request)))
                      (invalid-request! "create_item project must not be blank."))
                    (when (some str/blank? (:labels request))
                      (invalid-request! "create_item labels must not be blank."))
                    (when (some str/blank? (:blockedBy request))
                      (invalid-request! "create_item blockers must not be blank.")))
    "update_item" (do
                    (when-not (seq (:item request)) (invalid-request! "update_item requires item."))
                    (when-not (some #(contains? request %) (concat [:comment :addLabels :removeLabels] work-item-fields))
                      (invalid-request! "update_item requires comment, addLabels, or removeLabels, or a work-item field."))
                    (when (and (contains? request :comment) (str/blank? (:comment request)))
                      (invalid-request! "update_item comment must not be blank."))
                    (when (some str/blank? (concat (:addLabels request) (:removeLabels request)))
                      (invalid-request! "update_item labels must not be blank."))
                    (when (some str/blank? (:blockedBy request))
                      (invalid-request! "update_item blockers must not be blank.")))
    "create_change_request" (when (str/blank? (:title request))
                              (invalid-request! "create_change_request requires title."))
    "update_change_request" (do
                              (when-not (or (contains? request :title) (contains? request :body))
                                (invalid-request! "update_change_request requires title or body."))
                              (when (and (contains? request :title) (str/blank? (:title request)))
                                (invalid-request! "update_change_request title must not be blank.")))
    "comment_item" (do
                     (when-not (seq (:item request)) (invalid-request! "comment_item requires item."))
                     (when (str/blank? (:body request)) (invalid-request! "comment_item requires body.")))
    "comment_change_request" (when (str/blank? (:body request))
                               (invalid-request! "comment_change_request requires body."))
    nil)
  (when-let [unknown (seq (set/difference (set (keys request))
                                          (get action-fields (:action request))))]
    (invalid-request!
     (case (:action request)
       ("create_change_request" "update_change_request")
       (str (:action request) " accepts only title and body.")
       "comment_change_request" "comment_change_request accepts only body."
       (str (:action request) " does not accept fields: "
            (str/join ", " (sort (map name unknown))) "."))))
  request)

(defn assoc-work-item-fields [core-request request]
  (cond-> core-request
    (contains? request :state) (assoc :state (:state request))
    (contains? request :priority) (assoc :priority (:priority request))
    (contains? request :dueAt) (assoc :due-at (:dueAt request))
    (contains? request :availableAt) (assoc :available-at (:availableAt request))
    (contains? request :blockedBy) (assoc :blocked-by (vec (:blockedBy request)))))

(defn core-request [request]
  (case (:action request)
    "create_item" (assoc-work-item-fields
                    (cond-> {:action :create-item
                             :title (:title request)
                             :description (or (:description request) "")
                             :labels (vec (or (:labels request) []))}
                      (:project request) (assoc :project-ref (:project request)))
                    request)
    "update_item" (assoc-work-item-fields
                    (cond-> {:action :update-item
                             :item-ref (:item request)
                             :add-labels (vec (or (:addLabels request) []))
                             :remove-labels (vec (or (:removeLabels request) []))}
                      (contains? request :comment) (assoc :comment (:comment request)))
                    request)
    "create_change_request" {:action :create-change-request :title (:title request) :body (or (:body request) "")}
    "update_change_request" (cond-> {:action :update-change-request}
                              (contains? request :title) (assoc :title (:title request))
                              (contains? request :body) (assoc :body (:body request)))
    "comment_item" {:action :comment-item :item-ref (:item request) :body (:body request)}
    "comment_change_request" {:action :comment-change-request :body (:body request)}
    (cond-> {:action (case (:action request) "link_existing" :link-existing "create_new" :create-new)
             :labels (vec (or (:labels request) []))}
      (:item request) (assoc :item-ref (:item request)) (:parent request) (assoc :parent-ref (:parent request))
      (:project request) (assoc :project-ref (:project request)) (:title request) (assoc :title (:title request)))))
(defn canonicalize [value]
  (cond (map? value) (into (sorted-map-by #(compare (str %1) (str %2))) (map (fn [[key item]] [key (canonicalize item)])) value)
        (sequential? value) (mapv canonicalize value) :else value))
(defn sha256 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (json/generate-string (canonicalize value)) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))
(defn proposal-id [proposal] (str "lp2_" (subs (sha256 proposal) 0 24)))

(defn approval-context
  [runtime]
  (let [{:keys [target-state state-id state-name project issue-type assignee-id]}
        (get-in runtime [:config :tracker])
        target (first (remove #(str/blank? (str %)) [target-state state-id state-name]))]
    {:trackerScope (domain/identity-data ((get-in runtime [:tracker :configured-scope])))
     :trackerSettings (cond-> {}
                        target (assoc :targetState target)
                        (not (str/blank? (str project))) (assoc :project project)
                        (not (str/blank? (str issue-type))) (assoc :issueType issue-type)
                        (not (str/blank? (str assignee-id))) (assoc :assigneeId assignee-id))}))
(defn preview-proposal [runtime request]
  (validate-request! request)
  (let [profile (get-in runtime [:config :profile])
        proposal (cond-> (core/preview runtime (core-request request))
                   (contains? #{"link_existing" "create_new" "create_item" "update_item" "comment_item"} (:action request)) (assoc :approval-context (approval-context runtime))
                   profile (assoc :profile profile)
                   true (assoc :config-id (sha256 (:config runtime))))]
    (assoc proposal :proposal-id (proposal-id proposal))))
(defn preview-data [runtime request] (proposal->wire (preview-proposal runtime request)))
(defn apply-data! [runtime request approval]
  (let [proposal (preview-proposal runtime request)]
    (when-not (= approval (:proposal-id proposal)) (throw (ex-info "Approval does not match the current proposal. Preview again before applying." {:code :stale-proposal})))
    (let [result (core/apply! runtime proposal)]
      (cond-> {:proposalId (:proposal-id proposal)
               :item (wire-entity (:item result))
               :changeRequest (wire-entity (:change-request result))}
        (:change-request-update result) (assoc :changeRequestUpdate (:change-request-update result))
        (:comment result) (assoc :comment (:comment result))))))
(defn forge-runtime [options]
  (let [app-config (config/load-config (or (:config options) config/default-config-path)
                                       (:profile options)
                                       [:forge])]
    {:config app-config :forge (adapters/build app-config :forge forge/registry)}))
(defn tracker-runtime [options]
  (let [app-config (config/load-config (or (:config options) config/default-config-path)
                                       (:profile options)
                                       [:tracker])]
    {:config app-config :tracker (adapters/build app-config :tracker tracker/registry)}))
(defn runtime [options]
  (let [app-config (config/load-config (or (:config options) config/default-config-path)
                                       (:profile options))]
    (adapters/runtime app-config forge/registry tracker/registry)))
(defn request-runtime [options request]
  (case (:action request)
    ("create_change_request" "update_change_request" "comment_change_request") (forge-runtime options)
    ("create_item" "update_item" "comment_item") (tracker-runtime options)
    (runtime options)))
(defn execute-command [command args]
  (if (= "version" command)
    {:name "ttt" :version product-version :agentApiVersion schema-version :capabilities ["named-profiles" "configuration-status" "inspect-current-change-request" "search-items" "search-projects" "search-labels" "list-items" "semantic-search" "link-existing" "create-new" "create-items" "update-items" "item-lifecycle" "item-priority" "item-due-dates" "item-availability" "item-blockers" "create-change-request" "update-change-requests" "comment-items" "comment-change-requests" "approval-gated-apply"]}
    (let [options (parse-options args)]
      (case command
        "status" (status-data options)
        "inspect" (inspect-data (forge-runtime options))
        "search" (search-data (:tracker (runtime options)) options)
        "list" (list-data (:tracker (tracker-runtime options)) options)
        "preview" (let [request (parse-request options)]
                    (preview-data (request-runtime options request) request))
        "apply" (let [request (parse-request options)]
                  (apply-data! (request-runtime options request) request (require-option options :approve)))
        (throw (ex-info (str "Unknown agent command: " command) {:code :invalid-command}))))))

(defn run [args]
  (let [command (or (first args) "unknown")]
    (try {:exit 0 :envelope (success command (execute-command command (rest args)))}
         (catch Exception ex {:exit 2 :envelope (failure command ex)}))))
