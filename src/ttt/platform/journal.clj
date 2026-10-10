(ns ttt.platform.journal)

(defn owner-only! [file directory?]
  (when-not (and (.setReadable file false false) (.setWritable file false false)
                 (.setReadable file true true) (.setWritable file true true)
                 (or (not directory?)
                     (and (.setExecutable file false false) (.setExecutable file true true))))
    (throw (ex-info "Cannot restrict recovery journal permissions to its owner."
                    {:code :journal-unavailable}))))

(defn sync-directory! [directory]
  (with-open [channel (java.nio.channels.FileChannel/open
                       (.toPath directory)
                       (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/READ]))]
    (.force channel true)))

(defn write-record! [file record]
  (try
    (let [parent (.getParentFile file)
          temp (java.io.File/createTempFile "ttt-" ".edn" parent)]
      (try
        (owner-only! temp false)
        (with-open [output (java.io.FileOutputStream. temp)]
          (.write output (.getBytes (pr-str record) "UTF-8"))
          (.force (.getChannel output) true))
        (java.nio.file.Files/move (.toPath temp) (.toPath file)
                                  (into-array java.nio.file.CopyOption
                                              [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                               java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
        (sync-directory! parent)
        (finally (.delete temp))))
    (catch Exception ex
      (throw (ex-info "Cannot persist and sync the recovery checkpoint; inspect the journal before retrying."
                      {:code :journal-unavailable} ex)))))

(defn with-lock [parent name run]
  (try
    (.mkdirs parent)
    (owner-only! parent true)
    (sync-directory! (.getParentFile (.getAbsoluteFile parent)))
    (catch Exception ex
      (throw (ex-info "Cannot initialize and sync the recovery directory."
                      {:code :journal-unavailable} ex))))
  (let [lock (java.io.File. parent name)]
    (when-not (.mkdir lock)
      (throw (ex-info "This target or batch is locked. Inspect the active process before removing an abandoned lock."
                      {:code :journal-locked})))
    (try (run) (finally (.delete lock)))))

(defn target-lock-name [target]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str (mapv target [:provider :kind :container :id])) "UTF-8"))]
    (str (format "%064x" (java.math.BigInteger. 1 digest)) ".target-lock")))
