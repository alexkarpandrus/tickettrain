(ns ttt.platform.remote
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(def max-detail-length 500)

(def provider-names
  {:asana "Asana"
   :bitbucket "Bitbucket"
   :gitlab "GitLab"
   :jira "Jira"
   :linear "Linear"})

(def detail-keys
  [:message :detail :error_description :errorMessages :errors :error])

(defn provider-name
  [provider]
  (or (get provider-names provider) (name provider)))

(defn decode-body
  [body]
  (if (and (string? body) (not (str/blank? body)))
    (try
      (json/parse-string body true)
      (catch Exception _ body))
    body))

(declare detail-parts)

(defn map-detail-parts
  [value]
  (let [known (keep #(when (contains? value %) (get value %)) detail-keys)]
    (if (seq known)
      (mapcat detail-parts known)
      (mapcat (fn [[key item]]
                (map #(str (name key) ": " %) (detail-parts item)))
              value))))

(defn detail-parts
  [value]
  (cond
    (string? value) (when-not (str/blank? value) [(str/trim value)])
    (map? value) (map-detail-parts value)
    (sequential? value) (mapcat detail-parts value)
    :else []))

(defn sanitize-detail
  [value]
  (when-let [detail (some-> value str (str/replace #"\s+" " ") str/trim not-empty)]
    (let [redacted (-> detail
                       (str/replace #"(?i)\bBearer\s+\S+" "Bearer <redacted>")
                       (str/replace #"(?i)\bBasic\s+[A-Za-z0-9+/=]{12,}" "Basic <redacted>")
                       (str/replace #"(?i)\b(token|password|secret|api[-_ ]?key)\s*[:=]\s*\S+" "$1=<redacted>"))]
      (if (> (count redacted) max-detail-length)
        (str (subs redacted 0 (- max-detail-length 3)) "...")
        redacted))))

(defn error-detail
  [body]
  (let [decoded (decode-body body)
        values (if (map? decoded)
                 (keep #(when (contains? decoded %) (get decoded %)) detail-keys)
                 [decoded])]
    (some->> values
             (mapcat detail-parts)
             distinct
             (str/join "; ")
             sanitize-detail)))

(defn request!
  [provider send]
  (let [response (try
                   (send)
                   (catch Exception ex
                     (let [detail (sanitize-detail (.getMessage ex))]
                       (throw (ex-info
                               (str "Unable to reach " (provider-name provider) " API."
                                    (when detail (str " " detail)))
                               {:code :provider-unavailable
                                :provider provider
                                :detail detail}
                               ex)))))
        status (:status response)
        body (decode-body (:body response))]
    (when (and (number? status) (>= status 400))
      (let [detail (error-detail body)]
        (throw (ex-info
                (str (provider-name provider) " API request failed with status " status "."
                     (when detail (str " " detail)))
                {:code :remote-api-error
                 :provider provider
                 :status status
                 :detail detail
                 :body body}))))
    body))
