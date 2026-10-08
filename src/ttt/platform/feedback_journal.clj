(ns ttt.platform.feedback-journal
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
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

(defn owner-only! [file directory?]
  (when-not (and (.setReadable file false false) (.setWritable file false false)
                 (.setReadable file true true) (.setWritable file true true)
                 (or (not directory?)
                     (and (.setExecutable file false false) (.setExecutable file true true))))
    (throw (ex-info "Cannot restrict feedback journal permissions to its owner."
                    {:code :feedback-journal-unavailable}))))


(defn sync-directory! [directory]
  (with-open [channel (java.nio.channels.FileChannel/open
                       (.toPath directory)
                       (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/READ]))]
    (.force channel true)))

(defn write-batch! [batch-id batch]
  (let [file (path batch-id)
        parent (.getParentFile file)
        temp (java.io.File/createTempFile "feedback-" ".edn" parent)]
    (try
      (owner-only! temp false)
      (with-open [output (java.io.FileOutputStream. temp)]
        (.write output (.getBytes (pr-str batch) "UTF-8"))
        (.force (.getChannel output) true))
      (java.nio.file.Files/move (.toPath temp) (.toPath file)
                                (into-array java.nio.file.CopyOption
                                            [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                             java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (sync-directory! parent)
      (finally (.delete temp)))))

(defn- with-lock [name run]
  (let [parent (directory)
        _ (.mkdirs parent)
        _ (owner-only! parent true)
        _ (sync-directory! (.getParentFile (.getAbsoluteFile parent)))
        lock (java.io.File. parent name)]
    (when-not (.mkdir lock)
      (throw (ex-info "This feedback target or batch is locked. Inspect any active process before removing an abandoned lock."
                      {:code :feedback-batch-locked})))
    (try (run) (finally (.delete lock)))))

(defn with-batch-lock [batch-id run]
  (with-lock (str batch-id ".lock") run))

(defn with-target-lock [change-request run]
  (let [target (:native-ref change-request)
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str (mapv target [:provider :kind :container :id])) "UTF-8"))
        key (format "%064x" (java.math.BigInteger. 1 digest))]
    (with-lock (str key ".target-lock") run)))
