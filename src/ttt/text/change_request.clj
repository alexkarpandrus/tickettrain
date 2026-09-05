(ns ttt.text.change-request
  (:require [clojure.string :as str]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.text.markdown :as markdown]))

(def item-marker-pattern #"^<!-- ttt:item (\S+) -->$")
(def malformed-options
  {:code :malformed-managed-section
   :message "The change request body has malformed or duplicate ttt markers."})

(defn context-label
  [context]
  (or (:label context) "Parent"))

(defn context-title
  [context]
  (or (when (= :project (:kind context))
        (or (:title context) (:name context)))
      (:display-id context)
      (:identifier context)
      (:title context)
      (:name context)
      (:slugId context)
      "Unknown"))

(defn markers
  [app-config]
  (let [{:keys [body-begin-marker body-end-marker]}
        (config/change-request-settings app-config)]
    [body-begin-marker body-end-marker]))

(defn context-link-line?
  [line]
  (when (str/starts-with? line "- ")
    (when-let [separator (str/index-of line ": [")]
      (markdown/link-text? (subs line (+ separator 2))))))

(defn outer-section
  [body app-config]
  (let [[begin-marker end-marker] (markers app-config)]
    (markdown/parse-section body begin-marker end-marker malformed-options)))

(defn parse-managed
  [body app-config]
  (when-let [section (outer-section body app-config)]
    (let [{:keys [section-title]} (config/change-request-settings app-config)
          lines (->> (str/split-lines (:content section))
                     (remove str/blank?)
                     vec)
          [heading & content-lines] lines
          [_ key] (re-matches item-marker-pattern (or (first content-lines) ""))
          [item-ref content-lines] (if key
                                     [(domain/key-identity key) (rest content-lines)]
                                     [nil content-lines])
          [issue-link & context-links] content-lines]
      (when-not (and (= (str "## " (markdown/escape-label section-title)) heading)
                     (or (nil? key)
                         (and item-ref (= :tracker-item (:kind item-ref))))
                     (markdown/prefixed-link? (or issue-link "") "- Issue: ")
                     (<= (count context-links) 1)
                     (every? context-link-line? context-links))
        (throw (ex-info (:message malformed-options)
                        {:code (:code malformed-options)})))
      (assoc section
             :item-ref item-ref
             :issue-link issue-link))))

(defn managed-item-ref
  [body app-config]
  (:item-ref (parse-managed body app-config)))

(defn managed-issue-identifier
  [body app-config]
  (some->> (:issue-link (parse-managed body app-config))
           (re-find #"^- Issue: \[([A-Za-z0-9]+-\d+)\]")
           second))

(defn managed-section
  [app-config item context]
  (let [{:keys [section-title body-begin-marker body-end-marker]}
        (config/change-request-settings app-config)]
    (str body-begin-marker "\n"
         "## " (markdown/escape-label section-title) "\n\n"
         (when-let [item-ref (:ref item)]
           (str "<!-- ttt:item "
                (domain/identity-key item-ref)
                " -->\n"))
         "- Issue: " (markdown/link (domain/display-id item) (:url item)) "\n"
         (when context
           (str "- " (markdown/escape-label (context-label context)) ": "
                (markdown/link (context-title context) (:url context)) "\n"))
         body-end-marker)))

(defn upsert-managed-section
  [body app-config item context]
  (let [existing (or body "")
        parsed (parse-managed existing app-config)
        section (managed-section app-config item context)]
    (markdown/upsert-section existing parsed section)))

(defn strip-managed-section
  [body app-config]
  (if-let [section (outer-section body app-config)]
    (str/trim (str (:before section) (:after section)))
    (str/trim (or body ""))))
