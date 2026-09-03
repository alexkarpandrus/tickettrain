(ns ttt.main
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.adapters :as adapters]
            [ttt.agent :as agent]
            [ttt.config :as config]
            [ttt.core :as core]
            [ttt.forge :as forge]
            [ttt.shell :as shell]
            [ttt.tracker :as tracker]
            [ttt.ui :as ui]
            [ttt.workflow :as workflow]))

(def help-text
  (str/join
   "\n"
   ["ttt"
    ""
    "Non-interactive API (schema v2):"
    "  ttt version"
    "  ttt inspect"
    "  ttt search --kind item|project|label --query TEXT"
    "  ttt preview --request JSON"
    "  ttt preview --request-file PATH"
    "  ttt apply --request JSON --approve PROPOSAL_ID"
    "  ttt apply --request-file PATH --approve PROPOSAL_ID"
    ""
    "Interactive workflow:"
    "  ttt --interactive --parent \"parent item key or fuzzy text\""
    "  ttt -i --project \"project name or slug\""
    ""
    "Interactive options:"
    "  -i, --interactive Run the prompt-driven workflow"
    "  --config PATH     Config file path"
    "  --parent TEXT     Parent item key or fuzzy search text"
    "  --project TEXT    Project name or slug for a top-level item"
    "  --title TEXT      Override the tracker item title"
    "  --yes             Skip the final confirmation prompt"
    "  --dry-run         Print actions without creating or updating anything"
    "  --help            Show this help"]))

(def cli-spec
  {:interactive {:alias :i :coerce :boolean}
   :config {}
   :parent {}
   :project {}
   :title {}
   :yes {:coerce :boolean}
   :dry-run {:coerce :boolean}
   :help {:coerce :boolean}})

(defn parse-args
  [args]
  (let [{:keys [opts args]} (cli/parse-args args {:spec cli-spec})
        allowed-keys (set (keys cli-spec))
        unknown-keys (seq (remove allowed-keys (keys opts)))
        missing-value-option (first
                              (for [k [:config :parent :project :title]
                                    :when (= true (get opts k))]
                                k))]
    (when unknown-keys
      (throw (ex-info (str "Unknown option: --" (name (first unknown-keys)))
                      {:arg (first unknown-keys)})))
    (when missing-value-option
      (throw (ex-info (str "--" (name missing-value-option) " requires a value.")
                      {:option missing-value-option})))
    (let [first-positional (first (remove #(str/starts-with? % "--") args))]
      (cond-> {:config-path config/default-config-path}
        (:interactive opts) (assoc :interactive true)
        (:config opts) (assoc :config-path (:config opts))
        (:parent opts) (assoc :parent (:parent opts))
        (:project opts) (assoc :project (:project opts))
        (:title opts) (assoc :title (:title opts))
        (:yes opts) (assoc :yes true)
        (:dry-run opts) (assoc :dry-run true)
        (:help opts) (assoc :help true)
        (and (nil? (:parent opts))
             (nil? (:project opts))
             first-positional)
        (assoc :parent first-positional)))))


(defn exception-chain
  [ex]
  (take-while some? (iterate #(.getCause %) ex)))


(defn print-error!
  [ex]
  (let [chain (vec (exception-chain ex))
        github-connectivity? (some #(= :github-connectivity (:kind (ex-data %))) chain)
        command-error (some #(when-let [command (:command (ex-data %))]
                               {:command command
                                :exit (:exit (ex-data %))
                                :stderr (shell/first-nonblank-line (:err (ex-data %)))
                                :stdout (shell/first-nonblank-line (:out (ex-data %)))})
                            chain)
        tracker-error (some #(when-let [errors (:errors (ex-data %))]
                               (some-> errors first :message))
                            chain)]
    (binding [*out* *err*]
      (println (ui/error (str "❌ " (.getMessage ex))))
      (doseq [cause (rest chain)]
        (println (ui/muted (str "Caused by: " (.getMessage cause)))))
      (when tracker-error
        (println (ui/muted (str "Tracker says: " tracker-error))))
      (when-let [{:keys [command exit stderr stdout]} command-error]
        (println (ui/muted (str "Command: " command)))
        (println (ui/muted (str "Exit code: " exit)))
        (when stderr
          (println (ui/muted (str "stderr: " stderr))))
        (when (and (not stderr) stdout)
          (println (ui/muted (str "stdout: " stdout)))))
      (when github-connectivity?
        (println (ui/muted "The forge is temporarily unreachable. Existing-change-request create-and-link has no automatic crash recovery; check the tracker before retrying."))))))

(defn execute!
  [options]
  (core/ensure-selection-input! (assoc options :usage help-text))
  (let [app-config (config/load-config (:config-path options))
        runtime (adapters/runtime app-config forge/registry tracker/registry)]
    (workflow/execute! runtime options)))

(defn run-agent!
  [args]
  (let [{:keys [exit envelope]} (agent/run args)]
    (println (json/generate-string envelope))
    exit))

(def machine-commands
  #{"version" "inspect" "search" "preview" "apply"})

(defn interactive-request?
  [args]
  (boolean (some #{"-i" "--interactive"} args)))

(defn run-interactive!
  [args]
  (try
    (let [options (parse-args args)]
      (if (:help options)
        (println help-text)
        (execute! options)))
    (catch Exception ex
      (if (= :aborted (:code (ex-data ex)))
        (println (ui/warning "⚠ Aborted."))
        (do (print-error! ex)
            (when-let [usage (:usage (ex-data ex))]
              (binding [*out* *err*]
                (println)
                (println usage)))))
      1)))

(defn -main
  [& args]
  (cond
    (contains? machine-commands (first args))
    (let [exit (run-agent! args)]
      (when (pos? exit)
        (System/exit exit)))

    (interactive-request? args)
    (when-let [exit (run-interactive! args)]
      (System/exit exit))

    (or (empty? args) (some #{"--help" "-h"} args))
    (println help-text)

    :else
    (do
      (binding [*out* *err*]
        (println "Use a non-interactive command or pass --interactive."))
      (System/exit 1))))
