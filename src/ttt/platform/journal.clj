(ns ttt.platform.journal)

(defn owner-only! [file directory?]
  ;; ponytail: POSIX-only journals; add a verified ACL backend if other filesystems are required.
  (try
    (let [path (.toPath file)
          options (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])
          permissions (java.nio.file.attribute.PosixFilePermissions/fromString
                       (if directory? "rwx------" "rw-------"))]
      (when (java.nio.file.Files/isSymbolicLink path)
        (throw (java.io.IOException. "Recovery storage must not be a symbolic link.")))
      (java.nio.file.Files/setAttribute path "posix:permissions" permissions options)
      (when-not (= permissions (java.nio.file.Files/getAttribute path "posix:permissions" options))
        (throw (java.io.IOException. "Owner-only permissions were not enforced."))))
    (catch Exception ex
      (throw (ex-info "Cannot enforce owner-only recovery journal permissions on this filesystem."
                      {:code :journal-unavailable} ex)))))

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
