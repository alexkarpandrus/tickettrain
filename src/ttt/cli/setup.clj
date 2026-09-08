(ns ttt.cli.setup
  (:require [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.cli.prompt :as prompt]
            [ttt.cli.ui :as ui]
            [ttt.config :as config]
            [ttt.providers.forge :as forge]
            [ttt.providers.tracker :as tracker]))

(defn provider-entries
  [registry]
  (sort-by (fn [[provider-id descriptor]]
             [(get descriptor :setup-order Integer/MAX_VALUE) (name provider-id)])
           registry))

(defn provider-label
  [provider-id descriptor]
  (or (:display-name descriptor) (name provider-id)))

(defn choose-provider
  [role registry current]
  (let [options (vec (provider-entries registry))
        default-index (or (first (keep-indexed
                                  (fn [index [provider-id _]]
                                    (when (= provider-id current) index))
                                  options))
                          0)]
    (println)
    (println (ui/headline (str "Choose your " (name role) ":")))
    (doseq [[index [provider-id descriptor]] (map-indexed vector options)]
      (println (str "  " (inc index) ") " (provider-label provider-id descriptor)
                    (when (= index default-index) " (current)"))))
    (first (nth options
                (prompt/choose-index (count options) (name role) default-index)))))

(defn environment-value
  [{:keys [env]}]
  (let [environment (config/merge-env-config
                     (config/load-dotenv)
                     (into {} (System/getenv)))]
    (some #(some-> (get environment %) str/trim not-empty)
          (cond
            (string? env) [env]
            (sequential? env) env
            :else []))))

(defn selected-role-config
  [app-config role provider-id descriptor]
  (let [settings (:setup-settings descriptor)
        setting-keys (map :key settings)
        same-provider? (= provider-id (get-in app-config [role :provider]))
        persisted (if same-provider?
                    (select-keys (get app-config role) setting-keys)
                    {})
        environment (into {}
                          (keep (fn [{:keys [key] :as setting}]
                                  (when-let [value (environment-value setting)]
                                    [key value])))
                          settings)]
    (assoc (merge persisted environment) :provider provider-id)))

(defn collect-required-settings
  [app-config role descriptor]
  (reduce
   (fn [delta {:keys [key label env secret?]}]
     (if-not (config/missing-setting? (get-in app-config [role key]))
       delta
       (let [environment-variable (if (string? env) env (first env))
             message (str label " (" environment-variable "):")
             value (if secret?
                     (prompt/ask-secret message environment-variable)
                     (prompt/ask message))]
         (when (str/blank? value)
           (throw (ex-info
                   (str label " is required. Set " environment-variable
                        " or re-run `ttt setup`.")
                   {:code :aborted})))
         (assoc-in delta [role key] value))))
   {}
   (filter :required? (:setup-settings descriptor))))

(defn run-provider-setup
  [app-config descriptor]
  (when-let [setup-fn (:setup descriptor)]
    (setup-fn app-config)))

(defn setup!
  []
  (println (ui/headline "tickettrain setup"))
  (println "Choose a forge and tracker. Press Enter to keep the current choice.")
  (let [loaded (config/load-config)
        forge-id (choose-provider :forge forge/registry (config/forge-provider loaded))
        tracker-id (choose-provider :tracker tracker/registry (config/tracker-provider loaded))
        forge-descriptor (adapters/descriptor forge/registry :forge forge-id)
        tracker-descriptor (adapters/descriptor tracker/registry :tracker tracker-id)
        selected (assoc loaded
                        :forge (selected-role-config loaded :forge forge-id forge-descriptor)
                        :tracker (selected-role-config loaded :tracker tracker-id tracker-descriptor))
        credentials (config/deep-merge
                     (collect-required-settings selected :forge forge-descriptor)
                     (collect-required-settings selected :tracker tracker-descriptor))
        app-config (config/deep-merge selected credentials)
        forge-label (provider-label forge-id forge-descriptor)
        tracker-label (provider-label tracker-id tracker-descriptor)
        _ (println)
        _ (println (ui/accent (str "Checking " forge-label " → " tracker-label "...")))
        forge-delta (run-provider-setup app-config forge-descriptor)
        tracker-delta (run-provider-setup app-config tracker-descriptor)
        deltas (config/deep-merge
                (select-keys app-config [:forge :tracker])
                forge-delta
                tracker-delta)
        path (config/write-local-config! deltas #{:forge :tracker})]
    (println (ui/success (str "Configured " forge-label " → " tracker-label ".")))
    (println (ui/muted (str "Saved to " path " (owner-only).")))
    (println (ui/success "Setup complete. Run `ttt version` to verify."))))
