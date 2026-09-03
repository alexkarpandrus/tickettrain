(ns ttt.setup
  (:require [ttt.adapters :as adapters]
            [ttt.config :as config]
            [ttt.forge :as forge]
            [ttt.tracker :as tracker]
            [ttt.ui :as ui]))

(defn run-provider-setup
  [app-config role registry provider-fn]
  (let [provider-id (provider-fn app-config)
        descriptor (adapters/descriptor registry role provider-id)]
    (when-let [setup-fn (:setup descriptor)]
      (setup-fn app-config))))

(defn setup!
  []
  (let [app-config (config/load-config)
        forge-delta (run-provider-setup app-config :forge forge/registry config/forge-provider)
        tracker-delta (run-provider-setup app-config :tracker tracker/registry config/tracker-provider)
        deltas (merge forge-delta tracker-delta)]
    (when (seq deltas)
      (let [path (config/write-local-config! deltas)]
        (println (ui/success (str "✓ Wrote config to " path)))))
    (println (ui/success "Setup complete. Run `ttt version` to verify."))))
