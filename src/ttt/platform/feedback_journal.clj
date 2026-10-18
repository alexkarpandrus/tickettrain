(ns ttt.platform.feedback-journal
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [ttt.platform.journal :as storage]
            [ttt.platform.shell :as shell]))

(defn directory []
  (java.io.File. (str/trim (shell/run "git" "rev-parse" "--git-common-dir")) "ttt-feedback"))

(defn path [batch-id]
  (java.io.File. (directory) (str batch-id ".edn")))

(defn read-batch [batch-id]
  (let [file (path batch-id)]
    (when (.exists file)
      (let [batch (try (edn/read-string (slurp file))
                       (catch Exception ex
                         (throw (ex-info "Cannot read the feedback recovery journal; preserve it for inspection."
                                         {:code :feedback-journal-invalid} ex))))
            target (get-in batch [:proposal :change-request :native-ref])]
        (when-not (and (= 1 (:version batch)) (map? (:proposal batch))
                       (keyword? (:provider target)) (= :change-request (:kind target))
                       (every? #(and (string? %) (not (str/blank? %)))
                               [(:container target) (:id target)])
                       (vector? (:outcomes batch))
                       (= (count (:outcomes batch)) (count (get-in batch [:proposal :operations])))
                       (every? #(contains? #{:pending :started :succeeded :failed :unknown} (:status %))
                               (:outcomes batch)))
          (throw (ex-info "Invalid feedback recovery journal; preserve it for inspection."
                          {:code :feedback-journal-invalid})))
        batch))))

(defn with-storage-errors [run]
  (try (run)
       (catch Exception ex
         (case (:code (ex-data ex))
           :journal-unavailable (throw (ex-info (.getMessage ex) {:code :feedback-journal-unavailable} ex))
           :journal-locked (throw (ex-info (.getMessage ex) {:code :feedback-batch-locked} ex))
           (throw ex)))))

(defn write-batch! [batch-id batch]
  (with-storage-errors #(storage/write-record! (path batch-id) batch)))

(defn with-batch-lock [batch-id run]
  (with-storage-errors #(storage/with-lock (directory) (str batch-id ".lock") run)))

(defn with-target-lock [change-request run]
  (with-storage-errors #(storage/with-lock (directory)
                                          (storage/target-lock-name (:native-ref change-request)) run)))
