(ns ttt.cli.prompt
  (:require [clojure.string :as str]
            [ttt.cli.ui :as ui]))

(defn ask
  [message]
  (print (str message " "))
  (flush)
  (some-> (read-line) str/trim))

(defn system-console
  []
  (System/console))

(defn read-console-password
  [console]
  (.readPassword console))

(defn ask-secret
  ([message]
   (ask-secret message nil))
  ([message environment-variable]
   (if-let [console (system-console)]
     (do
       (print (str message " "))
       (flush)
       (if-let [password (read-console-password console)]
         (str/trim (String. password))
         (throw (ex-info "Input closed. No credential was saved." {:code :aborted}))))
     (throw (ex-info
             (str "Secure credential input is unavailable. Set "
                  (or environment-variable "the provider environment variable")
                  " and re-run `ttt setup`.")
             {:code :aborted})))))

(defn confirm?
  [message]
  (contains? #{"y" "yes"}
             (some-> (ask (str message " [y/N]")) str/lower-case)))

(defn choose-index
  ([max-index entity-label]
   (choose-index max-index entity-label nil))
  ([max-index entity-label default-index]
   (loop []
     (let [default-hint (when (some? default-index)
                          (str "; Enter for " (inc default-index)))
           response (ask (format "Choose a %s [1-%d%s]:"
                                 entity-label max-index (or default-hint "")))]
       (cond
         (nil? response)
         (throw (ex-info "Input closed. No selection was saved." {:code :aborted}))

         (and (str/blank? response) (some? default-index))
         default-index

         :else
         (if-let [parsed (try
                           (Integer/parseInt response)
                           (catch Exception _ nil))]
           (if (<= 1 parsed max-index)
             (dec parsed)
             (do
               (println (ui/warning "Selection out of range."))
               (recur)))
           (do
             (println (ui/warning "Enter a number."))
             (recur))))))))
