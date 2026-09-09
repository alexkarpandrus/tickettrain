(ns ttt.core
  (:require [clojure.string :as str]
            [ttt.text.change-request :as change-request]
            [ttt.domain :as domain]
            [ttt.text.links :as links]))

(defn inspect
  [runtime]
  ((get-in runtime [:forge :inspect-current])))

(defn configured-scope
  [runtime]
  ((get-in runtime [:tracker :configured-scope])))

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
  ([runtime request] (preview runtime (inspect runtime) request))
  ([runtime source request]
   (if (= :create-change-request (:action request))
     (standalone-change-request-proposal source request)
     (preview-tracker-link runtime source request))))

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
  (if (= :create-change-request (:action proposal))
    (let [source (:source proposal)]
      {:item nil
       :change-request (create-change-request! runtime source (:change-request-intent proposal))
       :change-request-update nil})
    (apply-tracker-link! runtime proposal)))
