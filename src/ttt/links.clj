(ns ttt.links
  (:require [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.markdown :as markdown]))

(def begin-marker "<!-- ttt:pull-requests:begin -->")
(def end-marker "<!-- ttt:pull-requests:end -->")
(def section-title "Pull requests")
(def source-marker-pattern #"^<!-- ttt:source (\S+) -->$")
(def malformed-options
  {:code :malformed-managed-section
   :message "The tracker description has malformed or duplicate ttt markers."})

(defn source-marker
  [change-request]
  (str "<!-- ttt:source "
       (domain/identity-key (:ref change-request))
       " -->"))

(defn source-link
  [change-request]
  (str "- "
       (markdown/link
        (str (:display-id change-request) " — " (:title change-request))
        (:url change-request))))

(defn malformed!
  []
  (throw (ex-info (:message malformed-options)
                  {:code (:code malformed-options)})))

(defn parse-entry
  [[marker link]]
  (let [[_ key] (re-matches source-marker-pattern marker)
        source (some-> key domain/key-identity)]
    (when-not (and source (markdown/prefixed-link? link "- "))
      (malformed!))
    {:source source
     :marker marker
     :link link}))

(defn parse-managed
  [description]
  (when-let [section (markdown/parse-section description
                                             begin-marker
                                             end-marker
                                             malformed-options)]
    (let [lines (->> (str/split-lines (:content section))
                     (remove str/blank?)
                     vec)
          [heading & entry-lines] lines]
      (when-not (and (= (str "## " section-title) heading)
                     (even? (count entry-lines)))
        (malformed!))
      (let [entries (mapv parse-entry (partition 2 entry-lines))
            sources (map (comp domain/identity-data :source) entries)]
        (when-not (= (count sources) (count (distinct sources)))
          (malformed!))
        (assoc section :entries entries)))))

(defn managed-entries
  [description]
  (or (:entries (parse-managed description)) []))

(defn render-managed-section
  [entries]
  (str begin-marker "\n"
       "## " section-title "\n\n"
       (str/join "\n" (mapcat (juxt :marker :link) entries)) "\n"
       end-marker))

(defn normalized-change-request
  [repo-slug change-request]
  (assoc change-request
         :ref (domain/contained-identity :github
                                         :change-request
                                         repo-slug
                                         (:number change-request))
         :display-id (str repo-slug "#" (:number change-request))))

(defn upsert-change-request
  ([description repo-slug change-request]
   (upsert-change-request description
                          (normalized-change-request repo-slug change-request)))
  ([description change-request]
   (let [existing (or description "")
         parsed (parse-managed existing)
         source (:ref change-request)
         entries (->> (or (:entries parsed) [])
                      (remove #(domain/same-identity? source (:source %)))
                      vec
                      (#(conj % {:source source
                                 :marker (source-marker change-request)
                                 :link (source-link change-request)})))
         section (render-managed-section entries)]
     (markdown/upsert-section existing parsed section))))
