(ns ttt.platform.transition-journal
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ttt.platform.journal :as storage]
            [ttt.platform.shell :as shell]))

(defn directory []
  (java.io.File. (str/trim (shell/run "git" "rev-parse" "--git-common-dir")) "ttt-item-transitions"))

(defn path [batch-id] (java.io.File. (directory) (str batch-id ".edn")))

(defn nonblank? [value] (and (string? value) (not (str/blank? value))))

(defn read-batch [batch-id]
  (let [file (path batch-id)]
    (when (.exists file)
      (let [batch (try (edn/read-string (slurp file))
                       (catch Exception ex
                         (throw (ex-info "Cannot read the item transition recovery journal; preserve it for inspection."
                                         {:code :item-transition-journal-invalid} ex))))
            proposal (:proposal batch)
            target (get-in proposal [:item :native-ref])
            operations (:operations proposal)]
        (when-not (and (= 1 (:version batch)) (= :transition-item (:action proposal))
                       (= batch-id (get-in proposal [:request :batch-id]))
                       (keyword? (:provider target)) (= :tracker-item (:kind target))
                       (every? nonblank? [(:container target) (:id target) (:config-id proposal) (:proposal-id proposal)])
                       (vector? operations) (seq operations)
                       (vector? (:outcomes batch)) (= (count operations) (count (:outcomes batch)))
                       (every? (fn [[operation outcome]]
                                 (and (contains? #{:comment :transition} (:type operation))
                                      (contains? #{:pending :started :succeeded :failed :unknown} (:status outcome))
                                      (or (not= :succeeded (:status outcome))
                                          (and (= (:id target) (get-in outcome [:result :item-id]))
                                               (case (:type operation)
                                                 :comment (and (nonblank? (get-in outcome [:result :comment-id]))
                                                               (string? (get-in outcome [:result :comment-body])))
                                                 :transition (= (:transition-id operation)
                                                                (get-in outcome [:result :transition-id])))))))
                               (map vector operations (:outcomes batch))))
          (throw (ex-info "Invalid item transition recovery journal; preserve it for inspection."
                          {:code :item-transition-journal-invalid})))
        batch))))

(defn with-storage-errors [run]
  (try (run)
       (catch Exception ex
         (case (:code (ex-data ex))
           :journal-unavailable (throw (ex-info (.getMessage ex) {:code :item-transition-journal-unavailable} ex))
           :journal-locked (throw (ex-info (.getMessage ex) {:code :item-transition-batch-locked} ex))
           (throw ex)))))

(defn write-batch! [batch-id batch]
  (with-storage-errors #(storage/write-record! (path batch-id) batch)))

(defn with-batch-lock [batch-id run]
  (with-storage-errors #(storage/with-lock (directory) (str batch-id ".lock") run)))

(defn with-target-lock [item run]
  (with-storage-errors #(storage/with-lock (directory) (storage/target-lock-name (:native-ref item)) run)))
