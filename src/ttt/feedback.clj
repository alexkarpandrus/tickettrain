(ns ttt.feedback
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.feedback-journal :as journal]
            [ttt.platform.remote :as remote]))

(def operation-fields
  {"reply" #{:type :discussion :body}
   "edit_note" #{:type :discussion :note :body}
   "resolve_discussion" #{:type :discussion :resolved}
   "update_reviewers" #{:type :reviewers :replace}})

(defn invalid! [message]
  (throw (ex-info message {:code :invalid-request})))

(defn nonblank? [value] (and (string? value) (not (str/blank? value))))
(defn native-id? [value] (and (string? value) (boolean (re-matches #"[1-9]\d*" value))))

(defn validate-operation! [operation]
  (let [type (:type operation) fields (get operation-fields type)]
    (when-not (and (map? operation) fields
                   (set/subset? (set (keys operation)) fields))
      (invalid! "Unsupported feedback operation or fields."))
    (when (contains? fields :discussion)
      (when-not (nonblank? (:discussion operation)) (invalid! "Feedback requires a discussion ID.")))
    (when (contains? fields :body)
      (when-not (nonblank? (:body operation)) (invalid! "Feedback requires a non-blank body.")))
    (case type
      "edit_note" (when-not (nonblank? (:note operation)) (invalid! "edit_note requires a native note ID string."))
      "resolve_discussion" (when-not (boolean? (:resolved operation)) (invalid! "resolved must be a boolean."))
      "update_reviewers"
      (do
        (when-not (and (vector? (:reviewers operation)) (every? nonblank? (:reviewers operation)))
          (invalid! "reviewers must be an array of native reviewer ID strings."))
        (when (and (contains? operation :replace) (not (boolean? (:replace operation))))
          (invalid! "replace must be a boolean."))
        (when (and (empty? (:reviewers operation)) (not (true? (:replace operation))))
          (invalid! "An empty reviewer array requires explicit replace: true.")))
      nil)))

(defn validate-request! [request]
  (when-not (and (map? request)
                 (set/subset? (set (keys request)) #{:action :changeRequest :batchId :expectedHead :operations})
                 (native-id? (:changeRequest request))
                 (string? (:batchId request))
                 (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,119}" (:batchId request))
                 (vector? (:operations request)) (seq (:operations request)))
    (invalid! "review_change_request requires changeRequest, a safe unique batchId, and a non-empty operations array."))
  (when (and (contains? request :expectedHead) (not (nonblank? (:expectedHead request))))
    (invalid! "expectedHead must be a non-blank source commit ID."))
  (doseq [operation (:operations request)] (validate-operation! operation))
  (let [targets (map #(select-keys % [:type :discussion :note]) (:operations request))]
    (when-not (= (count targets) (count (distinct targets)))
      (invalid! "A feedback batch cannot repeat an operation on the same target.")))
  request)

(defn require-capability! [runtime capability]
  (when-not (and (contains? (get-in runtime [:forge :capabilities]) capability)
                 (fn? (get-in runtime [:forge capability])))
    (throw (ex-info "The selected forge does not support review feedback."
                    {:code :unsupported-forge-operation}))))

(defn inspect [runtime reference]
  (require-capability! runtime :get-feedback)
  (let [repository ((get-in runtime [:forge :current-repo]))]
    {:repository repository
     :feedback ((get-in runtime [:forge :get-feedback]) repository reference)}))

(defn find-discussion [feedback reference]
  (or (some #(when (= reference (get-in % [:ref :id])) %) (:discussions feedback))
      (throw (ex-info "Discussion not found in the selected change request." {:code :discussion-not-found}))))

(defn find-note [discussion reference]
  (or (some #(when (= reference (get-in % [:ref :id])) %) (:notes discussion))
      (throw (ex-info "Note not found in the selected discussion." {:code :note-not-found}))))

(defn distinct-reviewers [reviewers]
  (reduce (fn [result reviewer]
            (if (some #(domain/same-identity? (:ref %) (:ref reviewer)) result)
              result (conj result reviewer)))
          [] reviewers))

(defn plan-operation [runtime feedback operation]
  (let [type (keyword (str/replace (:type operation) "_" "-"))
        discussion (when-let [id (:discussion operation)] (find-discussion feedback id))
        note (when-let [id (:note operation)] (find-note discussion id))]
    (when-not (contains? (get-in runtime [:forge :feedback-capabilities]) type)
      (throw (ex-info "The selected forge does not support this feedback operation."
                      {:code :unsupported-forge-operation :operation type})))
    (when (and (= :reply type) (:individual? discussion))
      (throw (ex-info "Cannot reply to an individual top-level note as a discussion." {:code :discussion-not-threaded})))
    (when (and (= :reply type) (false? (:replyable? discussion)))
      (throw (ex-info "The current user cannot reply to this discussion." {:code :discussion-not-replyable})))
    (when (and (= :edit-note type) (or (:system? note) (false? (:editable? note))))
      (throw (ex-info "The selected note is not editable by the current user." {:code :note-not-editable})))
    (when (and (= :resolve-discussion type)
               (or (not (:resolvable discussion))
                   (false? (get discussion (if (:resolved operation) :can-resolve? :can-unresolve?)))))
      (throw (ex-info "The selected discussion cannot reach this resolution state." {:code :discussion-not-resolvable})))
    (when (and (= :update-reviewers type) (false? (get-in feedback [:change-request :metadata-editable?])))
      (throw (ex-info "The change request no longer allows reviewer updates." {:code :change-request-not-editable})))
    (cond-> {:type type}
      discussion (assoc :discussion (dissoc discussion :notes))
      note (assoc :note note)
      (contains? operation :body) (assoc :body (:body operation))
      (contains? operation :resolved) (assoc :resolved (:resolved operation))
      (= :update-reviewers type)
      (assoc :replace (true? (:replace operation))
             :previous-reviewers (:reviewers feedback)
             :metadata-editable? (get-in feedback [:change-request :metadata-editable?])
             :reviewers (let [requested (mapv (get-in runtime [:forge :resolve-reviewer]) (:reviewers operation))]
                          (distinct-reviewers (if (:replace operation) requested
                                                  (concat (:reviewers feedback) requested))))))))

(defn assert-head! [expected feedback]
  (when-not (= expected (get-in feedback [:change-request :head-sha]))
    (throw (ex-info "The source head changed. Inspect feedback and preview a new batch."
                    {:code :stale-feedback-head}))))

(defn assert-target! [expected feedback]
  (when-not (domain/same-identity? (:native-ref expected) (get-in feedback [:change-request :native-ref]))
    (throw (ex-info "The native feedback target changed. Inspect feedback and approve a new batch."
                    {:code :stale-feedback-target}))))
(defn preview [runtime request]
  (require-capability! runtime :apply-feedback!)
  (let [saved (some-> (journal/read-batch (:batch-id request)) :proposal)
        {:keys [repository feedback]} (inspect runtime (:change-request-ref request))
        source {:repository repository :change-request (:change-request feedback)}]
    (when (:expected-head request) (assert-head! (:expected-head request) feedback))
    (if saved
      (do
        (when-not (and (= request (:request saved))
                       (domain/same-identity? (:ref repository) (get-in saved [:source :repository :ref])))
          (throw (ex-info "batchId already belongs to a different approved feedback request."
                          {:code :feedback-batch-mismatch})))
        (assert-target! (:change-request saved) feedback)
        (assert-head! (get-in saved [:change-request :head-sha]) feedback)
        (dissoc saved :proposal-id :profile :config-id))
      {:action :review-change-request :request request :source source
       :change-request (:change-request feedback)
       :operations (mapv #(plan-operation runtime feedback %) (:operations request))})))

(defn assert-operation-current! [feedback operation]
  (let [discussion (when-let [target (:discussion operation)]
                     (find-discussion feedback (get-in target [:ref :id])))
        current (case (:type operation)
                  :reply (select-keys discussion [:ref :individual? :replyable?])
                  :edit-note (select-keys (find-note discussion (get-in operation [:note :ref :id]))
                                          [:ref :body :updated-at :system? :editable?])
                  :resolve-discussion (select-keys discussion [:ref :resolvable :resolved :can-resolve? :can-unresolve?])
                  :update-reviewers {:reviewers (set (map :ref (:reviewers feedback)))
                                     :metadata-editable? (get-in feedback [:change-request :metadata-editable?])})
        expected (case (:type operation)
                   :reply (select-keys (:discussion operation) [:ref :individual? :replyable?])
                   :edit-note (select-keys (:note operation) [:ref :body :updated-at :system? :editable?])
                   :resolve-discussion (select-keys (:discussion operation) [:ref :resolvable :resolved :can-resolve? :can-unresolve?])
                   :update-reviewers {:reviewers (set (map :ref (:previous-reviewers operation)))
                                      :metadata-editable? (:metadata-editable? operation)})]
    (when-not (= current expected)
      (throw (ex-info "The feedback target changed since approval; do not overwrite it."
                      {:code :stale-feedback-target})))))

(defn rejected? [ex]
  (let [status (:status (ex-data ex))]
    (and (number? status) (<= 400 status 499) (not= 408 status))))

(defn attempt-operation! [runtime proposal batch index]
  (let [batch-id (get-in proposal [:request :batch-id])
        operation (get-in proposal [:operations index])
        started? (atom false)
        acknowledged (atom nil)]
    (try
      (let [feedback ((get-in runtime [:forge :get-feedback])
                      (get-in proposal [:source :repository])
                      (get-in proposal [:change-request :ref :id]))]
        (assert-target! (:change-request proposal) feedback)
        (assert-head! (get-in proposal [:change-request :head-sha]) feedback)
        (assert-operation-current! feedback operation))
      (journal/write-batch! batch-id (assoc-in batch [:outcomes index] {:status :started}))
      (reset! started? true)
      (let [result ((get-in runtime [:forge :apply-feedback!])
                    (get-in proposal [:source :repository]) (:change-request proposal) operation)
            updated (assoc-in batch [:outcomes index] {:status :succeeded :result result})]
        (reset! acknowledged result)
        (journal/write-batch! batch-id updated)
        updated)
      (catch Exception ex
        (let [outcome (if @acknowledged
                        {:status :succeeded :result @acknowledged}
                        {:status (if (or (not @started?) (rejected? ex)) :failed :unknown)
                         :error {:code (name (or (:code (ex-data ex)) :feedback-operation-failed))
                                 :message (remote/sanitize-detail (.getMessage ex))}})
              updated (assoc-in batch [:outcomes index] outcome)]
          (try (journal/write-batch! batch-id updated)
               updated
               (catch Exception journal-error
                 (assoc updated :recovery-error (remote/sanitize-detail (.getMessage journal-error))))))))))

(defn assert-readback! [feedback operations outcomes]
  (doseq [[operation outcome] (map vector operations outcomes)
          :when (= :succeeded (:status outcome))]
    (let [discussion (when-let [target (:discussion operation)]
                       (find-discussion feedback (get-in target [:ref :id])))
          observed? (case (:type operation)
                      :reply (= (:body operation)
                                (:body (find-note discussion (get-in outcome [:result :note-id]))))
                      :edit-note (= (:body operation)
                                    (:body (find-note discussion (get-in operation [:note :ref :id]))))
                      :resolve-discussion (= (:resolved operation) (:resolved discussion))
                      :update-reviewers (= (set (map :ref (:reviewers operation)))
                                           (set (map :ref (:reviewers feedback)))))]
      (when-not observed?
        (throw (ex-info "Final readback does not confirm an acknowledged feedback mutation; inspect before further action."
                        {:code :feedback-readback-mismatch}))))))

(defn apply-batch! [runtime proposal]
  (let [batch-id (get-in proposal [:request :batch-id])]
    (journal/with-batch-lock
     batch-id
     (fn []
       (journal/with-target-lock
        (:change-request proposal)
        (fn []
          (let [saved (journal/read-batch batch-id)
                _ (when (and saved (not= proposal (:proposal saved)))
                    (throw (ex-info "The approved batch or configuration changed; preserve its recovery journal."
                                    {:code :feedback-batch-mismatch})))
                initial (or saved {:version 1 :proposal proposal
                                   :outcomes (mapv (constantly {:status :pending}) (:operations proposal))})
                batch (loop [index 0 batch initial]
                        (if (= index (count (:operations proposal))) batch
                          (case (get-in batch [:outcomes index :status])
                            :succeeded (recur (inc index) batch)
                            (:started :unknown) batch
                            (let [updated (attempt-operation! runtime proposal batch index)]
                              (if (and (not (:recovery-error updated))
                                       (= :succeeded (get-in updated [:outcomes index :status])))
                                (recur (inc index) updated) updated)))))
                result {:change-request (:change-request proposal)
                        :operations (:operations proposal) :outcomes (:outcomes batch)
                        :recovery-error (:recovery-error batch)}
                result (try
                         (let [feedback ((get-in runtime [:forge :get-feedback])
                                         (get-in proposal [:source :repository])
                                         (get-in proposal [:change-request :ref :id]))]
                           (assert-target! (:change-request proposal) feedback)
                           (assoc result :feedback feedback))
                         (catch Exception ex
                           (assoc result :readback-error (remote/sanitize-detail (.getMessage ex)))))
                result (if-let [feedback (:feedback result)]
                         (try
                           (assert-head! (get-in proposal [:change-request :head-sha]) feedback)
                           (assert-readback! feedback (:operations proposal) (:outcomes batch))
                           result
                           (catch Exception ex
                             (assoc result :readback-error (remote/sanitize-detail (.getMessage ex)))))
                         result)]
            (when (or (:recovery-error result) (:readback-error result)
                      (not-every? #(= :succeeded (:status %)) (:outcomes batch)))
              (throw (ex-info "Feedback batch is incomplete. Inspect per-operation results; never resend an unknown reply."
                              {:code :feedback-partial-failure :partial-result result})))
            result)))))))
