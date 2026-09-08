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
        default-index (first (keep-indexed
                              (fn [index [provider-id _]]
                                (when (= provider-id current) index))
                              options))]
    (println)
    (println (ui/headline (str "Choose your " (name role) ":")))
    (doseq [[index [provider-id descriptor]] (map-indexed vector options)]
      (println (str "  " (inc index) ") " (provider-label provider-id descriptor)
                    (when (= index default-index) " (current)"))))
    (first (nth options
                (prompt/choose-index (count options) (name role) default-index)))))


(defn selected-role-config
  [app-config role provider-id descriptor]
  (let [setting-keys (map :key (:setup-settings descriptor))
        same-provider? (= provider-id (get-in app-config [role :provider]))
        persisted (if same-provider?
                    (select-keys (get app-config role) setting-keys)
                    {})]
    (assoc persisted :provider provider-id)))

(defn remove-environment-secrets
  [delta role descriptor environment-config credentials]
  (reduce
   (fn [clean {:keys [key secret?]}]
     (if (and secret?
              (contains? (get environment-config role) key)
              (not (contains? (get credentials role) key)))
       (update clean role #(when % (dissoc % key)))
       clean))
   (or delta {})
   (:setup-settings descriptor)))

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
  (let [loaded (config/load-file-config)
        forge-id (choose-provider :forge forge/registry (config/forge-provider loaded))
        tracker-id (choose-provider :tracker tracker/registry (config/tracker-provider loaded))
        forge-descriptor (adapters/descriptor forge/registry :forge forge-id)
        tracker-descriptor (adapters/descriptor tracker/registry :tracker tracker-id)
        selected (assoc loaded
                        :forge (selected-role-config loaded :forge forge-id forge-descriptor)
                        :tracker (selected-role-config loaded :tracker tracker-id tracker-descriptor))
        environment (config/merge-env-config
                     (config/load-dotenv)
                     (into {} (System/getenv)))
        environment-config (config/env-overrides selected environment)
        configured (config/deep-merge selected environment-config)
        credentials (config/deep-merge
                     (collect-required-settings configured :forge forge-descriptor)
                     (collect-required-settings configured :tracker tracker-descriptor))
        app-config (config/deep-merge configured credentials)
        forge-label (provider-label forge-id forge-descriptor)
        tracker-label (provider-label tracker-id tracker-descriptor)
        _ (println)
        _ (println (ui/accent (str "Checking " forge-label " → " tracker-label "...")))
        forge-delta (-> (run-provider-setup app-config forge-descriptor)
                        (remove-environment-secrets :forge forge-descriptor
                                                    environment-config credentials))
        tracker-delta (-> (run-provider-setup app-config tracker-descriptor)
                          (remove-environment-secrets :tracker tracker-descriptor
                                                      environment-config credentials))
        deltas (config/deep-merge
                (select-keys selected [:forge :tracker])
                credentials
                forge-delta
                tracker-delta)
        path (config/write-local-config! deltas #{:forge :tracker})]
    (println (ui/success (str "Configured " forge-label " → " tracker-label ".")))
    (println (ui/muted (str "Saved to " path " (owner-only).")))
    (println (ui/success "Setup complete. Run `ttt version` to verify."))))
