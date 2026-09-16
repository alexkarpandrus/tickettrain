(ns ttt.credentials
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def helper-name-pattern #"[A-Za-z0-9][A-Za-z0-9._-]*")

(defn helper-name
  [config environment]
  (when-let [value (some-> (or (get environment "TTT_CREDENTIAL_HELPER")
                                (:credential-helper config))
                            str
                            str/trim
                            not-empty)]
    (when-not (re-matches helper-name-pattern value)
      (throw (ex-info "Credential helper names may contain only letters, numbers, dot, underscore, and hyphen."
                      {:code :invalid-credential-helper})))
    value))

(defn helper-executable
  [helper]
  (str "docker-credential-" helper))

(defn detected-helper
  []
  (let [os (str/lower-case (System/getProperty "os.name"))
        candidates (cond
                     (str/includes? os "mac") ["osxkeychain"]
                     (str/includes? os "win") ["wincred"]
                     :else ["pass" "secretservice"])]
    (some #(when (fs/which (helper-executable %)) %) candidates)))

(defn credential-id
  [profile role provider key]
  (let [encode #(java.net.URLEncoder/encode (name %) "UTF-8")]
    (str "https://tickettrain.invalid/"
         (encode (or profile :default)) "/"
         (encode role) "/"
         (encode provider) "/"
         (encode key))))

(defn run-helper
  [helper action input]
  (let [executable (helper-executable helper)
        path (fs/which executable)]
    (when-not path
      (throw (ex-info (str "Credential helper not found: " executable ".")
                      {:code :credential-helper-unavailable
                       :helper helper})))
    (try
      (let [{:keys [exit out]}
            @(process/process {:in input :out :string :err :string}
                              (str path) action)]
        (if (zero? exit)
          (or out "")
          (throw (ex-info (str "Credential helper " helper " failed during " action ".")
                          {:code :credential-helper-failed
                           :helper helper
                           :action action}))))
      (catch clojure.lang.ExceptionInfo error
        (throw error))
      (catch Exception error
        (throw (ex-info (str "Credential helper " helper " failed during " action ".")
                        {:code :credential-helper-failed
                         :helper helper
                         :action action}
                        error))))))

(defn get-secret
  [helper id]
  (try
    (let [result (json/parse-string (run-helper helper "get" (str id "\n")) true)
          secret (:Secret result)]
      (when-not (string? secret)
        (throw (ex-info "Credential helper returned an invalid response."
                        {:code :credential-helper-invalid-response
                         :helper helper})))
      secret)
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Exception error
      (throw (ex-info "Credential helper returned an invalid response."
                      {:code :credential-helper-invalid-response
                       :helper helper}
                      error)))))

(defn store-secret!
  [helper id secret]
  (run-helper helper "store"
              (json/generate-string {:ServerURL id
                                     :Username "tickettrain"
                                     :Secret secret}))
  nil)

(defn erase-secret!
  [helper id]
  (run-helper helper "erase" (str id "\n"))
  nil)
