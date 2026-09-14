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

(defn resource-reference
  ([resource]
   (resource-reference resource (domain/display-id resource)))
  ([resource label]
   (if (seq (:url resource))
     (markdown/link label (:url resource))
     (markdown/escape-label label))))

(defn reference-text?
  [value allow-plain?]
  (or (markdown/link-text? value)
      (and allow-plain?
           (not (str/blank? value))
           (not (str/starts-with? value "[")))))

(defn prefixed-reference?
  [line prefix allow-plain?]
  (and (str/starts-with? line prefix)
       (reference-text? (subs line (count prefix)) allow-plain?)))

(defn context-link-line?
  [line allow-plain?]
  (when (str/starts-with? line "- ")
    (when-let [separator (str/index-of line ": ")]
      (reference-text? (subs line (+ separator 2)) allow-plain?))))

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
          [issue-link & context-links] content-lines
          allow-plain? (boolean item-ref)]
      (when-not (and (= (str "## " (markdown/escape-label section-title)) heading)
                     (or (nil? key)
                         (and item-ref (= :tracker-item (:kind item-ref))))
                     (prefixed-reference? (or issue-link "") "- Issue: " allow-plain?)
                     (<= (count context-links) 1)
                     (every? #(context-link-line? % allow-plain?) context-links))
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
         "- Issue: " (resource-reference item) "\n"
         (when context
           (str "- " (markdown/escape-label (context-label context)) ": "
                (resource-reference context (context-title context)) "\n"))
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
