(ns ttt.core
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [ttt.text.change-request :as change-request]
            [ttt.domain :as domain]
            [ttt.text.links :as links]))

(defn inspect
  [runtime]
  ((get-in runtime [:forge :inspect-current])))

(defn configured-scope
  [runtime]
  ((get-in runtime [:tracker :configured-scope])))

(defn require-tracker-operation!
  [runtime capability operation]
  (when-not (fn? (get-in runtime [:tracker capability]))
    (throw (ex-info (str "The tracker provider does not support " operation ".")
                    {:code :unsupported-capability
                     :provider (get-in runtime [:tracker :provider])
                     :capability capability}))))

(def work-item-capability-by-field
  {:state :item-lifecycle
   :priority :item-priority
   :due-at :item-due-dates
   :available-at :item-availability
   :blocked-by :item-blockers})

(def work-item-fields (set (keys work-item-capability-by-field)))

(defn requested-work-item-capabilities [request]
  (->> work-item-capability-by-field
       (keep (fn [[field capability]] (when (contains? request field) capability)))
       set))

(defn assert-work-item-capabilities! [runtime request]
  (let [requested (requested-work-item-capabilities request)
        supported (set (get-in runtime [:tracker :item-capabilities]))
        unsupported (set/difference requested supported)]
    (when (seq unsupported)
      (throw (ex-info "The tracker provider does not support the requested work-item fields."
                      {:code :unsupported-capability
                       :provider (get-in runtime [:tracker :provider])
                       :capabilities unsupported}))))
  request)

(defn assert-entity-scope!
  [runtime entity entity-kind]
  (let [scope (configured-scope runtime)]
    (when-not (domain/entity-in-scope? entity scope)
      (throw (ex-info (str "Resolved tracker " (name entity-kind)
                           " is outside the configured scope.")
                      {:code :tracker-scope-mismatch
                       :entity-kind entity-kind
                       :entity (:ref entity)
                       :expected-scope scope}))))
  entity)

(defn resolve-item!
  [runtime item-ref]
  (let [item ((get-in runtime [:tracker :resolve-item]) item-ref)]
    (when-not item
      (throw (ex-info (str "Tracker item not found: " item-ref)
                      {:code :tracker-item-not-found :item-ref item-ref})))
    (assert-entity-scope! runtime item :item)))

(defn work-item-intent
  [runtime request]
  (assert-work-item-capabilities! runtime request)
  (cond-> (select-keys request work-item-fields)
    (contains? request :blocked-by)
    (assoc :blocked-by (mapv #(resolve-item! runtime %) (:blocked-by request)))))

(defn validate-work-item-intent!
  [runtime action target intent]
  (when (and (= :update-item action)
             (some #(domain/same-identity? (:ref target) (:ref %))
                   (:blocked-by intent)))
    (throw (ex-info "A tracker item cannot block itself."
                    {:code :invalid-blocker
                     :item (:ref target)})))
  (when-let [validate (get-in runtime [:tracker :validate-work-item-intent!])]
    (validate action target intent))
  intent)

(defn resolve-parent!
  [runtime parent-ref]
  (let [parent ((get-in runtime [:tracker :resolve-parent-item]) parent-ref)]
    (when-not parent
      (throw (ex-info (str "Tracker parent not found: " parent-ref)
                      {:code :tracker-parent-not-found :parent-ref parent-ref})))
    (assert-entity-scope! runtime parent :parent)))

(defn resolve-project!
  [runtime project-ref]
  (let [project ((get-in runtime [:tracker :resolve-project]) project-ref)]
    (when-not project
      (throw (ex-info (str "Tracker project not found: " project-ref)
                      {:code :tracker-project-not-found :project-ref project-ref})))
    (assert-entity-scope! runtime project :project)))

(defn assert-parent-project!
  [parent project]
  (let [parent-project-ref (get-in parent [:project :ref])]
    (when (and parent project
               (not (and parent-project-ref
                         (domain/same-identity? parent-project-ref (:ref project)))))
      (throw (ex-info (str "Parent " (:display-id parent)
                           " is not in project " (:display-id project) ".")
                      {:code :parent-project-mismatch
                       :parent (:ref parent)
                       :project (:ref project)}))))
  {:parent parent :project project})

(defn validate-context!
  [runtime {:keys [parent project]}]
  (assert-parent-project!
   (when parent (assert-entity-scope! runtime parent :parent))
   (when project (assert-entity-scope! runtime project :project))))

(defn resolve-context
  [runtime request]
  (if-let [context (:context request)]
    (validate-context! runtime context)
    (assert-parent-project!
     (some->> (:parent-ref request) (resolve-parent! runtime))
     (some->> (:project-ref request) (resolve-project! runtime)))))

(defn item-labels
  [item]
  (vec (:labels item)))

(defn distinct-labels
  [current selected]
  (second
   (reduce (fn [[seen labels] label]
             (let [key (domain/identity-data (:ref label))]
               (if (contains? seen key)
                 [seen labels]
                 [(conj seen key) (conj labels label)])))
           [#{} []]
           (concat current selected))))

(defn label-key [label]
  (domain/identity-data (:ref label)))

(defn label-in-scope?
  [label scope]
  (or (empty? (:scopes label))
      (domain/entity-in-scope? label scope)))

(defn resolve-labels
  [runtime label-refs]
  (let [scope (configured-scope runtime)
        labels ((get-in runtime [:tracker :resolve-labels]) (or label-refs []) scope)]
    (doseq [label labels]
      (when-not (label-in-scope? label scope)
        (throw (ex-info "Resolved tracker label is outside the configured scope."
                        {:code :tracker-scope-mismatch
                         :entity-kind :label
                         :entity (:ref label)
                         :expected-scope scope}))))
    labels))

(defn base-item-description
  [app-config change-request]
  (let [body (change-request/strip-managed-section (:body change-request) app-config)]
    (if (str/blank? body) "_Change request body was empty._" body)))

(defn body-context
  [{:keys [parent project]}]
  (cond
    parent (assoc parent :label "Parent")
    project (assoc project :label "Project")
    :else nil))

(defn linked-item-context
  [item]
  (cond
    (:parent item) {:parent (:parent item)}
    (:project item) {:project (:project item)}
    :else nil))

(defn draft-change-request
  [request item]
  (let [title (or (:title request) (:title item))]
    (when (str/blank? title)
      (throw (ex-info "A title is required when the current branch has no change request."
                      {:code :change-request-title-required})))
    {:title title :body ""}))

(defn change-request-update
  [runtime source item context]
  (let [forge (:forge runtime)
        change-request (:change-request source)
        rendered-context (or context (linked-item-context item))]
    {:title ((:prefix-change-request-title forge) (:display-id item) (:title change-request))
     :body (change-request/upsert-managed-section
            (:body change-request) (:config runtime) item (body-context rendered-context))}))

(defn assert-link-target-valid!
  [runtime request change-request item]
  (let [linked-ref (change-request/managed-item-ref (:body change-request) (:config runtime))
        linked-display-id (change-request/managed-issue-identifier (:body change-request) (:config runtime))
        same-target? (and (= :link-existing (:action request)) item
                          (if linked-ref
                            (domain/same-identity? linked-ref (:ref item))
                            (= linked-display-id (:display-id item))))]
    (when (and (or linked-ref linked-display-id) (not same-target?))
      (throw (ex-info "The change request is already linked to another tracker item."
                      {:code :change-request-already-linked
                       :linked-item (or linked-ref linked-display-id)})))))


(defn change-request-intent
  [source content]
  (assoc content
         :base (get-in source [:repository :default-target-branch])
         :head (:branch source)))

(defn standalone-change-request-proposal
  [source request]
  (when (:change-request source)
    (throw (ex-info "The current branch already has a change request."
                    {:code :change-request-already-exists})))
  {:source source
   :action :create-change-request
   :request request
   :change-request-intent
   (change-request-intent source
                          (assoc (draft-change-request request nil)
                                 :body (or (:body request) "")))})

(defn update-change-request-proposal
  [source request]
  (let [change-request (:change-request source)]
    (when-not change-request
      (throw (ex-info "No current change request found."
                      {:code :change-request-not-found})))
    {:source source
     :action :update-change-request
     :request request
     :change-request change-request
     :change-request-update (select-keys request [:title :body])}))

(defn standalone-item-proposal
  [runtime request]
  (let [context (resolve-context runtime request)
        labels (resolve-labels runtime (:labels request))
        work-intent (validate-work-item-intent!
                     runtime :create-item context (work-item-intent runtime request))]
    {:action :create-item
     :request request
     :context context
     :labels labels
     :tracker-intent (merge {:title (:title request)
                             :description (:description request)
                             :labels labels}
                            work-intent)}))

(defn update-item-proposal
  [runtime request]
  (let [work-intent (work-item-intent runtime request)]
    (when (or (seq (:add-labels request))
              (seq (:remove-labels request))
              (seq work-intent))
      (require-tracker-operation! runtime :update-item! "item updates"))
    (when (contains? request :comment)
      (require-tracker-operation! runtime :comment-item! "item comments"))
    (let [item (resolve-item! runtime (:item-ref request))
          validated-work-intent (validate-work-item-intent!
                                 runtime :update-item item work-intent)
          current (item-labels item)
          requested-add (resolve-labels runtime (:add-labels request))
          requested-remove (resolve-labels runtime (:remove-labels request))
          add-keys (set (map label-key requested-add))
          remove-keys (set (map label-key requested-remove))]
      (when (some remove-keys add-keys)
        (throw (ex-info "update_item cannot add and remove the same label."
                        {:code :invalid-label-changes})))
      (let [current-keys (set (map label-key current))
            add (remove #(contains? current-keys (label-key %)) requested-add)
            removed (filter #(contains? current-keys (label-key %)) requested-remove)
            labels (->> (distinct-labels current add)
                        (remove #(contains? remove-keys (label-key %)))
                        vec)]
        (cond-> {:action :update-item
                 :request request
                 :item item
                 :labels labels
                 :label-changes {:add (vec add) :remove (vec removed)}
                 :tracker-intent (merge {:description (:description item) :labels labels}
                                        validated-work-intent)}
          (contains? request :comment) (assoc :comment {:body (:comment request)}))))))

(defn comment-item-proposal
  [runtime request]
  (let [item (resolve-item! runtime (:item-ref request))]
    {:action :comment-item
     :request request
     :item item
     :comment {:body (:body request)}}))

(defn comment-change-request-proposal
  [source request]
  (let [change-request (:change-request source)]
    (when-not change-request
      (throw (ex-info "No current change request found."
                      {:code :change-request-not-found})))
    {:source source
     :action :comment-change-request
     :request request
     :change-request change-request
     :comment {:body (:body request)}}))

(defn close-change-request-proposal
  [runtime source request]
  (let [repository (:repository source)
        change-request ((get-in runtime [:forge :get-change-request])
                        repository (:change-request-ref request))]
    (when-not (= "open" (:state change-request))
      (throw (ex-info "The change request is not open." {:code :change-request-not-open})))
    {:source (assoc source :change-request change-request)
     :action :close-change-request
     :request request
     :change-request change-request
     :comment (when-let [body (:comment request)] {:body body})}))

(defn preview-tracker-link
  [runtime source request]
  (let [action (:action request)
        item (when (= :link-existing action)
               (if-let [resolved (:item request)]
                 (assert-entity-scope! runtime resolved :item)
                 (resolve-item! runtime (:item-ref request))))
        context (when (= :create-new action) (resolve-context runtime request))
        change-request (:change-request source)
        _ (assert-link-target-valid! runtime request change-request item)
        planned-change-request (or change-request (draft-change-request request item))
        planned-source (assoc source :change-request planned-change-request)
        selected-labels (resolve-labels runtime (:labels request))
        labels (if item (distinct-labels (item-labels item) selected-labels) selected-labels)
        title (if item (:title item) (or (:title request) (:title change-request)))
        description (if change-request
                      (links/upsert-change-request
                       (if item (:description item)
                           (base-item-description (:config runtime) change-request))
                       change-request)
                      (if item (:description item)
                          (base-item-description (:config runtime) planned-change-request)))
        preview-item (or item {:display-id "<new tracker item>" :url "<created during apply>"})]
    {:source source
     :action action
     :request request
     :item item
     :context context
     :labels labels
     :tracker-intent (cond-> {:description description :labels labels}
                       (= :create-new action) (assoc :title title))
     :change-request-update (change-request-update runtime planned-source preview-item context)}))

(defn preview
  ([runtime request]
   (if (contains? #{:create-item :update-item :comment-item} (:action request))
     (preview runtime nil request)
     (preview runtime
              (if (or (= :close-change-request (:action request))
                      (and (= :link-existing (:action request)) (:change-request-ref request)))
                {:repository ((get-in runtime [:forge :current-repo]))}
                (inspect runtime))
              request)))
  ([runtime source request]
   (case (:action request)
     :create-item (standalone-item-proposal runtime request)
     :update-item (update-item-proposal runtime request)
     :create-change-request (standalone-change-request-proposal source request)
     :update-change-request (update-change-request-proposal source request)
     :comment-change-request (comment-change-request-proposal source request)
     :close-change-request (close-change-request-proposal runtime source request)
     :comment-item (comment-item-proposal runtime request)
     (preview-tracker-link runtime
                           (if (and (= :link-existing (:action request)) (:change-request-ref request))
                             (assoc source :change-request
                                    (or ((get-in runtime [:forge :get-change-request])
                                         (:repository source) (:change-request-ref request))
                                        (throw (ex-info "Change request not found."
                                                        {:code :change-request-not-found}))))
                             source)
                           request))))

(defn update-change-request!
  [runtime source update]
  (let [repository (:repository source)
        change-request (:change-request source)]
    ((get-in runtime [:forge :update-change-request!])
     (get-in repository [:ref :id])
     (get-in change-request [:ref :id])
     update)))

(defn create-item!
  [runtime context intent]
  ((get-in runtime [:tracker :create-item!]) (validate-context! runtime context) intent))

(defn update-item!
  [runtime item intent]
  (assert-entity-scope! runtime item :item)
  ((get-in runtime [:tracker :update-item!]) item intent))

(defn comment-item!
  [runtime item body]
  (assert-entity-scope! runtime item :item)
  ((get-in runtime [:tracker :comment-item!]) item body))

(defn comment-change-request!
  [runtime source body]
  ((get-in runtime [:forge :comment-change-request!])
   (get-in source [:repository :ref :id])
   (get-in source [:change-request :ref :id])
   body))

(defn close-change-request!
  [runtime source comment]
  ((get-in runtime [:forge :close-change-request!])
   (get-in source [:repository :ref :id])
   (get-in source [:change-request :ref :id])
   comment))

(defn create-change-request!
  [runtime source intent]
  (let [repository (:repository source)
        created ((get-in runtime [:forge :create-change-request!]) intent)]
    ((get-in runtime [:forge :identify-change-request]) repository created)))

(defn apply-with-new-change-request!
  [runtime proposal]
  ;; ponytail: provider writes are recoverable but not atomic; add checkpoints if retries prove insufficient.
  (let [source (:source proposal)
        item (case (:action proposal)
               :link-existing (:item proposal)
               :create-new (create-item! runtime (:context proposal) (:tracker-intent proposal)))
        planned-source (assoc source :change-request (draft-change-request (:request proposal) item))
        update (change-request-update runtime planned-source item (:context proposal))
        change-request (create-change-request! runtime source (change-request-intent source update))
        intent (assoc (:tracker-intent proposal)
                      :description (links/upsert-change-request (:description item) change-request))
        item (update-item! runtime item intent)]
    {:item item :change-request change-request :change-request-update update}))

(defn apply-tracker-link!
  [runtime proposal]
  (let [source (:source proposal)]
    (if-not (:change-request source)
      (apply-with-new-change-request! runtime proposal)
      (let [item (case (:action proposal)
                   :link-existing (update-item! runtime (:item proposal) (:tracker-intent proposal))
                   :create-new (create-item! runtime (:context proposal) (:tracker-intent proposal)))
            update (if (= :create-new (:action proposal))
                     (change-request-update runtime source item (:context proposal))
                     (:change-request-update proposal))]
        (update-change-request! runtime source update)
        {:item item :change-request (:change-request source) :change-request-update update}))))

(defn apply!
  [runtime proposal]
  (case (:action proposal)
    :create-item
    {:item (create-item! runtime (:context proposal) (:tracker-intent proposal))
     :change-request nil}

    :update-item
    (let [labels-changed? (some seq (vals (:label-changes proposal)))
          work-item-changed? (some #(contains? (:tracker-intent proposal) %) work-item-fields)
          item (if (or labels-changed? work-item-changed?)
                 (update-item! runtime (:item proposal) (:tracker-intent proposal))
                 (:item proposal))]
      (when-let [body (get-in proposal [:comment :body])]
        (comment-item! runtime item body))
      {:item item :change-request nil :comment (:comment proposal)})
    :comment-item
    (do
      (comment-item! runtime (:item proposal) (get-in proposal [:comment :body]))
      {:item (:item proposal) :change-request nil :comment (:comment proposal)})

    :comment-change-request
    (do
      (comment-change-request! runtime (:source proposal) (get-in proposal [:comment :body]))
      {:item nil :change-request (:change-request proposal) :comment (:comment proposal)})

    :close-change-request
    (do
      (close-change-request! runtime (:source proposal) (get-in proposal [:comment :body]))
      {:item nil
       :change-request (assoc (:change-request proposal) :state "closed")
       :comment (:comment proposal)})

    :create-change-request
    (let [source (:source proposal)]
      {:item nil
       :change-request (create-change-request! runtime source (:change-request-intent proposal))
       :change-request-update nil})

    :update-change-request
    (let [source (:source proposal)
          update (:change-request-update proposal)]
      (update-change-request! runtime source update)
      {:item nil
       :change-request (merge (:change-request source) update)
       :change-request-update update})

    (apply-tracker-link! runtime proposal)))
