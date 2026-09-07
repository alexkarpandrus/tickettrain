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

(def default-local-config-path
  (str (fs/file (app-home) "config" "ttt.local.edn")))

(def default-change-request-config
  {:body-begin-marker "<!-- ttt:begin -->"
   :body-end-marker "<!-- ttt:end -->"})

(def tracker-section-titles
  {:linear "Linear"
   :jira "Jira"
   :github-issues "GitHub Issues"
   :asana "Asana"})

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
                      (get env "LINEAR_WORKSPACE_URL"))
        tracker (cond-> {}
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
                         (str "https://linear.app/" workspace)))

                (get env "JIRA_EMAIL")
                (assoc :email (get env "JIRA_EMAIL"))

                (get env "JIRA_API_TOKEN")
                (assoc :api-token (get env "JIRA_API_TOKEN"))

                (get env "JIRA_SITE_URL")
                (assoc :site-url (get env "JIRA_SITE_URL"))

                (get env "JIRA_CLOUD_ID")
                (assoc :cloud-id (get env "JIRA_CLOUD_ID"))

                (get env "JIRA_PROJECT")
                (assoc :project (get env "JIRA_PROJECT"))

                  (get env "JIRA_ISSUE_TYPE")
                  (assoc :issue-type (get env "JIRA_ISSUE_TYPE"))

                  (get env "ASANA_TOKEN")
                  (assoc :token (get env "ASANA_TOKEN"))

                  (get env "ASANA_WORKSPACE")
                  (assoc :workspace (get env "ASANA_WORKSPACE")))
        forge (cond-> {}
                (get env "GITLAB_TOKEN")
                (assoc :token (get env "GITLAB_TOKEN"))

                (get env "GITLAB_BASE_URL")
                (assoc :base-url (get env "GITLAB_BASE_URL")))]
    (cond-> {}
      (seq tracker) (assoc :tracker tracker)
      (seq forge) (assoc :forge forge))))

(defn deep-merge
  [& maps]
  (letfn [(merge-entry [left right]
            (merge-with (fn [a b]
                          (if (and (map? a) (map? b))
                            (merge-entry a b)
                            b))
                        left right))]
    (reduce merge-entry {} maps)))

(defn read-edn-map
  [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(defn write-local-config!
  [config-map]
  (let [path (fs/file default-local-config-path)]
    (fs/create-dirs (fs/parent path))
    (spit path (pr-str config-map))
    (try (fs/set-posix-file-permissions path "rw-------")
         (catch Exception _ nil))
    path))

(defn merge-env-config
  "Merge .env values under the real process environment; real env wins."
  [dotenv system-env]
  (merge dotenv system-env))

(defn load-config
  ([] (load-config default-config-path))
  ([path]
   (let [config-path (fs/file path)
         dotenv (load-dotenv)
         env-config (env-overrides (merge-env-config dotenv (into {} (System/getenv))))]
     (when-not (fs/exists? config-path)
       (throw (ex-info (str "Config file not found: " path)
                       {:path path})))
     (-> (deep-merge (read-edn-map config-path)
                     (read-edn-map default-local-config-path)
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
        (assoc :change-request
               (if (str/blank? (:section-title change-request))
                 (assoc change-request
                        :section-title
                        (get tracker-section-titles
                             (:provider tracker)
                             (str/capitalize (name (:provider tracker)))))
                 change-request)))))

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
