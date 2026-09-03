(ns ttt.adapters
  (:require [clojure.set :as set]
            [ttt.config :as config]))

(def required-capabilities
  {:forge #{:current-branch
            :maybe-current-change-request
            :current-change-request
            :current-repo
            :inspect-current
            :identify-change-request
            :update-change-request!
            :create-change-request!
            :prefix-change-request-title}
   :tracker #{:configured-scope
              :search-parent-items
              :resolve-parent-item
              :resolve-item
              :search-projects
              :resolve-project
              :search-labels
              :resolve-labels
              :create-item!
              :update-item!}})

(defn provider
  [app-config role]
  (case role
    :forge (config/forge-provider app-config)
    :tracker (config/tracker-provider app-config)))

(defn descriptor
  [registry role provider-id]
  (or (get registry provider-id)
      (throw (ex-info (str "Unsupported " (name role) " provider: " provider-id)
                      {:code :unsupported-provider
                       :role role
                       :provider provider-id}))))

(defn assert-capabilities!
  [role adapter]
  (let [required (get required-capabilities role)
        declared (set (:capabilities adapter))
        missing-declarations (set/difference required declared)
        missing-functions (set (remove #(fn? (get adapter %)) required))]
    (when (or (seq missing-declarations) (seq missing-functions))
      (throw (ex-info (str "The " (name role) " adapter is missing required capabilities.")
                      {:code :missing-adapter-capabilities
                       :role role
                       :provider (:provider adapter)
                       :missing (set/union missing-declarations missing-functions)})))
    adapter))

(defn assert-descriptor!
  [role provider-id descriptor]
  (when-not (fn? (:build descriptor))
    (throw (ex-info "The provider registry descriptor must define a build function."
                    {:code :invalid-adapter-descriptor
                     :role role
                     :provider provider-id
                     :field :build})))
  (when (and (:validate-config! descriptor)
             (not (fn? (:validate-config! descriptor))))
    (throw (ex-info "The provider registry descriptor validation hook must be a function."
                    {:code :invalid-adapter-descriptor
                     :role role
                     :provider provider-id
                     :field :validate-config!})))
  descriptor)

(defn build
  [app-config role registry]
  (let [provider-id (provider app-config role)
        {:keys [build validate-config!]}
        (assert-descriptor! role
                            provider-id
                            (descriptor registry role provider-id))]
    (when validate-config!
      (validate-config! app-config))
    (let [adapter (build app-config)]
      (when-not (= provider-id (:provider adapter))
        (throw (ex-info "The adapter provider does not match its registry key."
                        {:code :adapter-provider-mismatch
                         :role role
                         :expected provider-id
                         :actual (:provider adapter)})))
      (-> adapter
          (assoc :role role)
          (#(assert-capabilities! role %))))))

(defn runtime
  [app-config forge-registry tracker-registry]
  {:config app-config
   :forge (build app-config :forge forge-registry)
   :tracker (build app-config :tracker tracker-registry)})
