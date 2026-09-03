(ns ttt.shell
  (:require [babashka.process :as process]
            [clojure.string :as str]))

(defn command-string
  [args]
  (str/join " " args))

(defn first-nonblank-line
  [text]
  (some->> (str/split-lines (or text ""))
           (map str/trim)
           (remove str/blank?)
           first))

(defn failure-message
  [args exit out err]
  (let [command (command-string args)
        detail (or (first-nonblank-line err)
                   (first-nonblank-line out))]
    (str command
         " failed"
         " (exit " exit ")"
         (when detail
           (str ": " detail)))))

(defn run
  [& args]
  (let [{:keys [exit out err]}
        @(apply process/process {:out :string :err :string} args)]
    (if (zero? exit)
      (str/trim (or out ""))
      (throw (ex-info (failure-message args exit out err)
                      {:args args
                       :command (command-string args)
                       :exit exit
                       :out (str/trim (or out ""))
                       :err (str/trim (or err ""))})))))
