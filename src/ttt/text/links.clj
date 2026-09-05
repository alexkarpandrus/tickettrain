(ns ttt.text.links
  (:require [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.text.markdown :as markdown]))

(def section-title "Pull requests")
(def heading (str "## " section-title))

(def legacy-begin-marker "<!-- ttt:pull-requests:begin -->")
(def legacy-end-marker "<!-- ttt:pull-requests:end -->")
(def legacy-source-pattern #"^<!-- ttt:source (\S+) -->$")

(def malformed-options
  {:code :malformed-managed-section
   :message "The tracker description has malformed or duplicate ttt sections. Keep hand-authored content outside the `## Pull requests` section."})

(defn source-link
  [change-request]
  (str "- "
       (markdown/link
        (str (:display-id change-request) " — " (:title change-request))
        (:url change-request))))

(defn malformed!
  []
  (throw (ex-info (:message malformed-options) {:code (:code malformed-options)})))

(defn parse-entry
  [line]
  (when-not (str/starts-with? line "- ")
    (malformed!))
  (let [link (subs line 2)]
    (if-let [url (markdown/link-destination link)]
      {:url url :link line}
      (malformed!))))

(defn strip-legacy-markers
  [text]
  (-> (or text "")
      (str/replace legacy-begin-marker "")
      (str/replace legacy-end-marker "")
      (str/replace #"\n{3,}" "\n\n")))

(defn parse-managed
  [description]
  (let [cleaned (strip-legacy-markers description)]
    (when-let [section (markdown/heading-section cleaned heading malformed-options)]
      (let [lines (->> (str/split-lines (:content section))
                       (drop 1)
                       (remove str/blank?)
                       (remove #(re-matches legacy-source-pattern %))
                       vec)]
        (assoc section :entries (mapv parse-entry lines))))))

(defn managed-entries
  [description]
  (or (:entries (parse-managed description)) []))

(defn render-managed-section
  [entries]
  (str heading "\n\n"
       (str/join "\n" (map :link entries))))

(defn normalized-change-request
  [repo-slug change-request]
  (assoc change-request
         :ref (domain/contained-identity :github :change-request repo-slug (:number change-request))
         :display-id (str repo-slug "#" (:number change-request))))

(defn upsert-change-request
  ([description repo-slug change-request]
   (upsert-change-request description (normalized-change-request repo-slug change-request)))
  ([description change-request]
   (let [cleaned (strip-legacy-markers (or description ""))
         parsed (parse-managed cleaned)
         link (source-link change-request)
         url (markdown/escape-destination (:url change-request))
         entries (->> (or (:entries parsed) [])
                      (remove #(= url (:url %)))
                      vec
                      (#(conj % {:url url :link link})))
         section (render-managed-section entries)]
     (markdown/upsert-section cleaned parsed section))))
