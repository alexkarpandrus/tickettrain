(ns ttt.cli.setup
  (:require [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.cli.prompt :as prompt]
            [ttt.cli.ui :as ui]
            [ttt.config :as config]
            [ttt.credentials :as credentials]
            [ttt.providers.forge :as forge]
            [ttt.providers.tracker :as tracker]))


(defn choose-provider
  [role registry current]
  (let [options (vec (sort-by (fn [[provider-id descriptor]]
                                [(:setup-order descriptor) (name provider-id)])
                              registry))
        default-index (first (keep-indexed
                              (fn [index [provider-id _]]
                                (when (= provider-id current) index))
                              options))]
    (println)
    (println (ui/headline (str "Choose your " (name role) ":")))
    (doseq [[index [_ descriptor]] (map-indexed vector options)]
      (println (str "  " (inc index) ") " (:display-name descriptor)
                    (when (= index default-index) " (current)"))))
    (first (nth options
                (prompt/choose-index (count options) (name role) default-index)))))


(defn selected-role-config
  [effective-config local-config role provider-id descriptor]
  (let [setting-keys (map :key (:setup-settings descriptor))
        same-provider? (= provider-id
                          (keyword (get-in effective-config [role :provider])))
        effective (if same-provider?
                    (select-keys (get effective-config role) setting-keys)
                    (zipmap setting-keys (repeat nil)))
        persisted (if same-provider?
                    (select-keys (get local-config role) setting-keys)
                    (zipmap setting-keys (repeat nil)))]
    {:effective (assoc effective :provider provider-id)
     :persisted (assoc persisted :provider provider-id)}))

(defn remove-environment-secrets
  [value role descriptor environment-config]
  (reduce
   (fn [clean {:keys [key secret?]}]
     (if (and secret?
              (contains? (get environment-config role) key)
              (contains? clean role))
       (update-in clean [role] dissoc key)
       clean))
   (or value {})
   (:setup-settings descriptor)))

(defn mask-environment-secrets
  [value role descriptor environment-config]
  (reduce
   (fn [masked {:keys [key secret?]}]
     (if (and secret? (contains? (get environment-config role) key))
       (assoc-in masked [role key] nil)
       masked))
   value
   (:setup-settings descriptor)))

(defn remove-secret-settings
  [value role descriptor]
  (reduce
   (fn [clean {:keys [key secret?]}]
     (if (and secret? (contains? clean role))
       (update-in clean [role] dissoc key)
       clean))
   (or value {})
   (:setup-settings descriptor)))

(defn prompted-secret?
  [prompted role descriptor]
  (some (fn [{:keys [key secret?]}]
          (and secret? (contains? (get prompted role) key)))
        (:setup-settings descriptor)))

(defn store-role-secrets!
  [helper app-config role provider-id descriptor environment-config]
  (reduce
   (fn [stored {:keys [key secret?]}]
     (let [value (get-in app-config [role key])]
       (if (or (not secret?)
               (contains? (get environment-config role) key)
               (config/missing-setting? value))
         stored
         (do
           (credentials/store-secret!
            helper
            (credentials/credential-id (:profile app-config) role provider-id key)
            value)
           (inc stored)))))
   0
   (:setup-settings descriptor)))


(defn abort-invalid-environment-settings!
  [app-config environment-config role descriptor]
  (doseq [{:keys [key env]}
          (filter :required? (:setup-settings descriptor))
          :when (and (contains? (get environment-config role) key)
                     (config/missing-setting? (get-in app-config [role key])))]
    (let [environment-variable (if (string? env) env (first env))]
      (throw (ex-info
              (str "Set a valid " environment-variable " or unset it, then rerun `ttt setup`.")
              {:code :aborted})))))

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
  ([] (setup! config/default-config-path nil))
  ([config-path profile]
   (println (ui/headline "tickettrain setup"))
   (println "Choose a forge and tracker. Press Enter to keep the current choice.")
   (let [local-config (config/load-local-config)
         loaded (config/load-file-config config-path local-config profile)
         active-profile (:profile loaded)
         local-profile (config/profile-layer local-config active-profile)
         forge-id (choose-provider :forge forge/registry (config/forge-provider loaded))
         tracker-id (choose-provider :tracker tracker/registry (config/tracker-provider loaded))
         forge-descriptor (adapters/descriptor forge/registry :forge forge-id)
         tracker-descriptor (adapters/descriptor tracker/registry :tracker tracker-id)
         forge-selection (selected-role-config loaded local-profile :forge forge-id forge-descriptor)
         tracker-selection (selected-role-config loaded local-profile :tracker tracker-id tracker-descriptor)
         selected (assoc loaded
                         :forge (:effective forge-selection)
                         :tracker (:effective tracker-selection))
         environment (config/merge-env-config
                      (config/load-dotenv)
                      (into {} (System/getenv)))
         environment-config (config/env-overrides selected environment)
         configured-helper (credentials/helper-name selected environment)
         helper (or configured-helper (credentials/detected-helper))
         helper-config (config/credential-overrides selected environment-config helper true)
         configured (config/deep-merge selected helper-config environment-config)
         _ (abort-invalid-environment-settings! configured environment-config :forge forge-descriptor)
         _ (abort-invalid-environment-settings! configured environment-config :tracker tracker-descriptor)
         prompted-settings (config/deep-merge
                            (collect-required-settings configured :forge forge-descriptor)
                            (collect-required-settings configured :tracker tracker-descriptor))
         app-config (cond-> (config/deep-merge configured prompted-settings)
                      helper (assoc :credential-helper helper))
         forge-label (:display-name forge-descriptor)
         tracker-label (:display-name tracker-descriptor)
         _ (println)
         _ (println (ui/accent (str "Checking " forge-label " → " tracker-label "...")))
         persisted-selected (-> {:forge (:persisted forge-selection)
                                 :tracker (:persisted tracker-selection)}
                                (mask-environment-secrets :forge forge-descriptor
                                                          environment-config)
                                (mask-environment-secrets :tracker tracker-descriptor
                                                          environment-config))
         persisted-environment (-> environment-config
                                   (remove-environment-secrets :forge forge-descriptor
                                                               environment-config)
                                   (remove-environment-secrets :tracker tracker-descriptor
                                                               environment-config))
         forge-delta (-> (run-provider-setup app-config forge-descriptor)
                         (remove-environment-secrets :forge forge-descriptor
                                                     environment-config))
         tracker-delta (-> (run-provider-setup app-config tracker-descriptor)
                           (remove-environment-secrets :tracker tracker-descriptor
                                                       environment-config))
         store-result (when helper
                        (try
                          {:count
                           (+ (store-role-secrets! helper app-config :forge forge-id forge-descriptor
                                                   environment-config)
                              (store-role-secrets! helper app-config :tracker tracker-id tracker-descriptor
                                                   environment-config))}
                          (catch Exception error
                            (if configured-helper
                              (throw error)
                              {:error error}))))
         auto-helper-failed? (boolean (:error store-result))
         stored-secrets (:count store-result)
         persist-helper? (boolean (and helper (or configured-helper (pos? (or stored-secrets 0)))))
         _ (when auto-helper-failed?
             (println (ui/warning (.getMessage (:error store-result)))))
         _ (when (and (or (nil? helper) auto-helper-failed?)
                      (or auto-helper-failed?
                          (prompted-secret? prompted-settings :forge forge-descriptor)
                          (prompted-secret? prompted-settings :tracker tracker-descriptor))
                      (not (prompt/confirm?
                            "No working system credential helper is available. Save credentials as owner-only plaintext?")))
             (throw (ex-info "No credential was saved. Configure TTT_CREDENTIAL_HELPER and rerun `ttt setup`."
                             {:code :aborted})))
         deltas (config/deep-merge
                 (select-keys persisted-selected [:forge :tracker])
                 persisted-environment
                 prompted-settings
                 forge-delta
                 tracker-delta)
         deltas (cond-> deltas
                  persist-helper? (assoc :credential-helper helper))
         deltas (if persist-helper?
                  (-> deltas
                      (remove-secret-settings :forge forge-descriptor)
                      (remove-secret-settings :tracker tracker-descriptor))
                  deltas)
         path (if active-profile
                (config/write-local-config! deltas #{:forge :tracker} active-profile)
                (config/write-local-config! deltas #{:forge :tracker}))]
     (println (ui/success (str "Configured " forge-label " → " tracker-label ".")))
     (println (ui/muted
               (if persist-helper?
                 (str "Saved settings to " path "; credentials use docker-credential-" helper ".")
                 (str "Saved to " path " (owner-only)."))))
     (println (ui/success "Setup complete. Run `ttt version` to verify.")))))
