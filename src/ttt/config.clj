(ns ttt.config
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn app-home
  []
  (or (System/getenv "TTT_HOME")
      (.getCanonicalPath (fs/file "."))))

(def default-config-path
  (str (fs/file (app-home) "config" "ttt.edn")))

(def default-dotenv-path
  (str (fs/file (app-home) ".env")))

(def default-change-request-config
  {:body-begin-marker "<!-- ttt:begin -->"
   :body-end-marker "<!-- ttt:end -->"
   :section-title "Linear"})

(declare normalize-config)

(defn parse-dotenv-line
  [line]
  (let [trimmed (str/trim line)]
    (when-not (or (str/blank? trimmed)
                  (str/starts-with? trimmed "#"))
      (let [[_ key value] (re-matches #"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$" trimmed)]
        (when key
          [key (-> value
                   str/trim
                   (str/replace #"^['\"]|['\"]$" ""))])))))

(defn load-dotenv
  ([] (load-dotenv default-dotenv-path))
  ([path]
   (let [dotenv-path (fs/file path)]
     (if (fs/exists? dotenv-path)
       (->> (slurp dotenv-path)
            str/split-lines
            (keep parse-dotenv-line)
            (into {}))
       {}))))

(defn env-overrides
  [env]
  (let [workspace (or (get env "LINEAR_WORKSPACE")
                      (get env "LINEAR_WORKSPACE_URL"))]
    {:tracker (cond-> {}
                (get env "LINEAR_API_KEY")
                (assoc :api-key (get env "LINEAR_API_KEY"))

                (get env "LINEAR_TEAM_ID")
                (assoc :team-id (get env "LINEAR_TEAM_ID"))

                (get env "LINEAR_ASSIGNEE_ID")
                (assoc :assignee-id (get env "LINEAR_ASSIGNEE_ID"))

                (get env "LINEAR_STATE_ID")
                (assoc :state-id (get env "LINEAR_STATE_ID"))

                (get env "LINEAR_STATE_NAME")
                (assoc :state-name (get env "LINEAR_STATE_NAME"))

                workspace
                (assoc :workspace-url
                       (if (str/starts-with? workspace "http")
                         workspace
                         (str "https://linear.app/" workspace))))}))

(defn deep-merge
  [& maps]
  (letfn [(merge-entry [left right]
            (merge-with (fn [a b]
                          (if (and (map? a) (map? b))
                            (merge-entry a b)
                            b))
                        left right))]
    (reduce merge-entry {} maps)))

(defn load-config
  ([] (load-config default-config-path))
  ([path]
   (let [config-path (fs/file path)
         dotenv (load-dotenv)
         env-config (env-overrides (merge (System/getenv) dotenv))]
     (when-not (fs/exists? config-path)
       (throw (ex-info (str "Config file not found: " path)
                       {:path path})))
     (-> (deep-merge (edn/read-string (slurp config-path))
                     env-config)
         normalize-config))))

(defn normalize-config
  [config]
  (let [tracker (deep-merge {:provider :linear}
                            (:tracker config))
        forge (deep-merge {:provider :github}
                          (:forge config))
        change-request (deep-merge default-change-request-config
                                   (:change-request config))]
    (-> config
        (assoc :tracker tracker)
        (assoc :forge forge)
        (assoc :change-request change-request))))

(defn tracker-provider
  [config]
  (keyword (or (get-in config [:tracker :provider]) :linear)))

(defn forge-provider
  [config]
  (keyword (or (get-in config [:forge :provider]) :github)))

(defn tracker-settings
  [config]
  (:tracker (normalize-config config)))

(defn change-request-settings
  [config]
  (:change-request (normalize-config config)))

(defn placeholder?
  [value]
  (and (string? value)
       (str/includes? value "YOUR_")))

(defn missing-setting?
  [value]
  (or (nil? value)
      (and (string? value) (str/blank? value))
      (placeholder? value)))

(defn assert-settings!
  [provider settings required-keys]
  (let [missing (->> required-keys
                     (filter #(missing-setting? (get settings %)))
                     vec)]
    (when (seq missing)
      (throw (ex-info
              (str (name provider) " config is missing values for: "
                   (str/join ", " (map name missing)))
              {:code :provider-config-invalid
               :provider provider
               :missing missing})))))
