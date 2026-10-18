(ns ttt.transitions
  (:require [clojure.string :as str]
            [ttt.platform.transition-journal :as journal]
            [ttt.platform.remote :as remote]))

(defn validate-request! [request]
  (when-not (and (string? (:item request)) (not (str/blank? (:item request)))
                 (string? (:transitionId request)) (not (str/blank? (:transitionId request)))
                 (string? (:batchId request))
                 (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]{0,119}" (:batchId request))
                 (or (not (contains? request :fields)) (map? (:fields request)))
                 (or (not (contains? request :comment))
                     (and (string? (:comment request)) (not (str/blank? (:comment request))))))
    (throw (ex-info "transition_item requires item, an exact transitionId, a unique safe batchId, and optional fields object."
                    {:code :invalid-request})))
  request)

(defn require-capability! [runtime capability]
  (when-not (and (contains? (get-in runtime [:tracker :capabilities]) capability)
                 (fn? (get-in runtime [:tracker capability])))
    (throw (ex-info "The selected tracker does not support exact native item transitions."
                    {:code :unsupported-tracker-operation :capability capability}))))

(defn preview [runtime request]
  (doseq [capability [:plan-item-transition :validate-item-transition! :apply-item-transition-operation! :read-item-transition]]
    (require-capability! runtime capability))
  (merge {:action :transition-item :request request
          :operations (cond-> []
                        (:comment request) (conj {:type :comment :body (:comment request)})
                        true (conj {:type :transition :transition-id (:transition-id request)
                                    :fields (:fields request)}))}
         ((get-in runtime [:tracker :plan-item-transition]) request)))

(defn assert-readback! [proposal observed outcomes]
  (when (and (every? #(= :succeeded (:status %)) outcomes)
             (not= (get-in proposal [:transition :to :id])
                   (get-in observed [:item :native-status :id])))
    (throw (ex-info "Native readback does not confirm the approved destination status. Inspect the issue before further action."
                    {:code :item-transition-readback-mismatch})))
  (doseq [[operation outcome] (map vector (:operations proposal) outcomes)
          :when (and (= :comment (:type operation)) (= :succeeded (:status outcome)))]
    (let [comment (some #(when (= (get-in outcome [:result :comment-id]) (:id %)) %) (:comments observed))]
      (when-not (and comment (= (get-in outcome [:result :comment-body]) (:body comment)))
        (throw (ex-info "Native readback does not confirm the acknowledged comment ID/body. Inspect before recovery."
                        {:code :item-comment-readback-mismatch}))))))

(defn readback [runtime proposal result]
  (try
    (let [observed ((get-in runtime [:tracker :read-item-transition]) proposal)
          result (assoc result :item (:item observed) :readback observed)]
      (try (assert-readback! proposal observed (:outcomes result))
           result
           (catch Exception ex
             (assoc result :readback-error (remote/sanitize-detail (.getMessage ex))))))
    (catch Exception ex
      (assoc result :readback-error (remote/sanitize-detail (.getMessage ex))))))

(defn batch-result [batch]
  {:kind :item-transition :item (get-in batch [:proposal :item])
   :native-context (get-in batch [:proposal :native-context])
   :operations (get-in batch [:proposal :operations]) :outcomes (:outcomes batch)
   :recovery-error (:recovery-error batch)})

(defn recovery-proposal [runtime batch]
  (let [proposal (:proposal batch)
        result (readback runtime proposal (batch-result batch))]
    (try
      (when (:readback-error result)
        (throw (ex-info (:readback-error result) {:code :item-transition-recovery-unavailable})))
      (when-not (= (select-keys (:item proposal) [:ref :native-ref])
                   (select-keys (:item result) [:ref :native-ref]))
        (throw (ex-info "The native item identity changed; preserve the recovery journal."
                        {:code :native-item-mismatch})))
      (when-not (some #(contains? #{:started :unknown} (:status %)) (:outcomes batch))
        (doseq [[operation outcome] (map vector (:operations proposal) (:outcomes batch))
                :when (not= :succeeded (:status outcome))]
          ((get-in runtime [:tracker :validate-item-transition!]) proposal operation)))
      proposal
      (catch Exception ex
        (throw (ex-info (.getMessage ex) (assoc (ex-data ex) :partial-result result) ex))))))

(defn complete! [result cause]
  (when (or (:recovery-error result) (:readback-error result)
            (not-every? #(= :succeeded (:status %)) (:outcomes result)))
    (throw (ex-info "Item transition is incomplete. Inspect native state and per-operation results; never resend an unknown write."
                    {:code :item-transition-partial-failure :partial-result result} cause)))
  result)

(defn rejected? [ex]
  ;; Nonstandard proxy errors such as 499 can follow an accepted write.
  (contains? #{400 401 403 404 405 409 410 413 414 415 422 429} (:status (ex-data ex))))

(defn attempt-operation! [runtime proposal batch index]
  (let [batch-id (get-in proposal [:request :batch-id])
        started? (atom false)
        acknowledged (atom nil)]
    (try
      (journal/write-batch! batch-id (assoc-in batch [:outcomes index] {:status :started}))
      (reset! started? true)
      (let [result ((get-in runtime [:tracker :apply-item-transition-operation!])
                    proposal (get-in proposal [:operations index]))
            updated (assoc-in batch [:outcomes index] {:status :succeeded :result result})]
        (reset! acknowledged result)
        (journal/write-batch! batch-id updated)
        updated)
      (catch Exception ex
        (let [outcome (if @acknowledged
                        {:status :succeeded :result @acknowledged}
                        {:status (if (or (not @started?) (= false (:write-started? (ex-data ex))) (rejected? ex))
                                   :failed :unknown)
                         :error (cond-> {:code (name (or (:code (ex-data ex)) :item-transition-operation-failed))
                                        :message (remote/sanitize-detail (.getMessage ex))}
                                  (:status (ex-data ex)) (assoc :status (:status (ex-data ex))))})
              updated (assoc-in batch [:outcomes index] outcome)]
          (try (journal/write-batch! batch-id updated)
               updated
               (catch Exception storage-ex
                 (assoc updated :recovery-error (remote/sanitize-detail (.getMessage storage-ex))))))))))

(defn apply! [runtime proposal]
  (let [batch-id (get-in proposal [:request :batch-id])]
    (journal/with-batch-lock
     batch-id
     #(journal/with-target-lock
       (:item proposal)
       (fn []
         (let [saved (journal/read-batch batch-id)
               _ (when (and saved (not= proposal (:proposal saved)))
                   (throw (ex-info "The approved batch changed; preserve its recovery journal."
                                   {:code :item-transition-batch-mismatch})))
               initial (or saved {:version 1 :proposal proposal
                                  :outcomes (mapv (constantly {:status :pending}) (:operations proposal))})
               _ (when saved (recovery-proposal runtime saved))
               batch (loop [index 0 batch initial]
                       (if (= index (count (:operations proposal))) batch
                         (case (get-in batch [:outcomes index :status])
                           :succeeded (recur (inc index) batch)
                           (:started :unknown) batch
                           (let [updated (attempt-operation! runtime proposal batch index)]
                             (if (and (not (:recovery-error updated))
                                      (= :succeeded (get-in updated [:outcomes index :status])))
                               (recur (inc index) updated) updated)))))]
           (complete! (readback runtime proposal (batch-result batch)) nil)))))))
