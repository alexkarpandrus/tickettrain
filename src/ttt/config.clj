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

(defn normalize-linear-workspace
  [workspace]
  (if (str/starts-with? workspace "http")
    workspace
    (str "https://linear.app/" workspace)))

(def provider-settings
  {[:forge :github] []
   [:forge :gitlab]
   [{:key :token :label "GitLab token" :env "GITLAB_TOKEN"
     :required? true :secret? true}
    {:key :base-url :env "GITLAB_BASE_URL"}]
   [:forge :bitbucket]
   [{:key :email :label "Bitbucket email" :env "BITBUCKET_EMAIL" :required? true}
    {:key :api-token :label "Bitbucket API token" :env "BITBUCKET_API_TOKEN"
     :required? true :secret? true}
    {:key :base-url :env "BITBUCKET_BASE_URL"}]
   [:tracker :linear]
   [{:key :api-key :label "Linear API key" :env "LINEAR_API_KEY"
     :required? true :secret? true}
    {:key :team-id :env "LINEAR_TEAM_ID"}
    {:key :assignee-id :env "LINEAR_ASSIGNEE_ID"}
    {:key :workspace-url :env ["LINEAR_WORKSPACE" "LINEAR_WORKSPACE_URL"]
     :transform normalize-linear-workspace}
    {:key :state-id :env "LINEAR_STATE_ID"}
    {:key :state-name :env "LINEAR_STATE_NAME"}
    {:key :target-state :env "TTT_TRACKER_STATE"}]
   [:tracker :jira]
   [{:key :email :label "Jira email" :env "JIRA_EMAIL" :required? true}
    {:key :api-token :label "Jira API token" :env "JIRA_API_TOKEN"
     :required? true :secret? true}
    {:key :site-url :label "Jira site URL" :env "JIRA_SITE_URL" :required? true}
    {:key :cloud-id :label "Jira cloud ID" :env "JIRA_CLOUD_ID" :required? true}
    {:key :project :env "JIRA_PROJECT"}
    {:key :issue-type :env "JIRA_ISSUE_TYPE"}
    {:key :target-state :env "TTT_TRACKER_STATE"}]
   [:tracker :github-issues]
   [{:key :target-state :env "TTT_TRACKER_STATE"}]
   [:tracker :asana]
   [{:key :token :label "Asana token" :env "ASANA_TOKEN"
     :required? true :secret? true}
    {:key :workspace :env "ASANA_WORKSPACE"}
    {:key :base-url}
    {:key :target-state :env "TTT_TRACKER_STATE"}]})

(defn setting-environment-value
  [environment {:keys [env transform]}]
  (when-let [value (some #(some-> (get environment %) str/trim not-empty)
                         (if (string? env) [env] env))]
    ((or transform identity) value)))

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
  [config environment]
  (reduce
   (fn [overrides role]
     (let [provider (keyword (get-in config [role :provider]))
           settings (get provider-settings [role provider])
           values (into {}
                        (keep (fn [{:keys [key] :as setting}]
                                (when-let [value (setting-environment-value environment setting)]
                                  [key value])))
                        settings)]
       (cond-> overrides
         (seq values) (assoc role values))))
   {}
   [:forge :tracker]))

(defn deep-merge
  [& maps]
  (letfn [(merge-entry [left right]
            (merge-with (fn [a b]
                          (if (and (map? a) (map? b))
                            (merge-entry a b)
                            b))
                        left right))]
    (reduce merge-entry {} maps)))

(defn merge-config-layer
  [base layer]
  (let [merged (deep-merge base layer)]
    (reduce
     (fn [config role]
       (let [role-layer (get layer role)
             default-provider ({:forge :github :tracker :linear} role)
             base-provider (keyword (or (get-in base [role :provider]) default-provider))]
         (if (and (contains? role-layer :provider)
                  (not= base-provider (keyword (:provider role-layer))))
           (assoc config role role-layer)
           config)))
     merged
     [:forge :tracker])))

(defn read-edn-map
  [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(defn write-local-config!
  ([config-map]
   (write-local-config! config-map #{}))
  ([config-map replace-sections]
   (let [path (fs/file default-local-config-path)
         existing (apply dissoc (or (read-edn-map path) {}) replace-sections)]
     (fs/create-dirs (fs/parent path))
     (spit path (pr-str (deep-merge existing config-map)))
     (try (fs/set-posix-file-permissions path "rw-------")
          (catch Exception _ nil))
     path)))

(defn merge-env-config
  "Merge .env values under the real process environment; real env wins."
  [dotenv system-env]
  (merge dotenv system-env))

(defn load-file-config
  ([] (load-file-config default-config-path))
  ([path]
   (let [config-path (fs/file path)]
     (when-not (fs/exists? config-path)
       (throw (ex-info (str "Config file not found: " path)
                       {:path path})))
     (-> (merge-config-layer
          (read-edn-map config-path)
          (read-edn-map default-local-config-path))
         normalize-config))))

(defn load-config
  ([] (load-config default-config-path))
  ([path]
   (let [base-config (load-file-config path)
         environment (merge-env-config (load-dotenv) (into {} (System/getenv)))
         env-config (env-overrides base-config environment)]
     (-> (deep-merge base-config env-config)
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
