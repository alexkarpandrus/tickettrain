(ns ttt.inference.typesafe
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.config :as config]
            [ttt.platform.remote :as remote]))

(def api-url "https://api.typesafe.ai/v1/systemone")
(def model "jev-latest")
(def max-candidates 254)

(defn api-key
  []
  (or (some-> (System/getenv "TYPESAFE_API_KEY") str/trim not-empty)
      (some-> (get (config/load-dotenv) "TYPESAFE_API_KEY") str/trim not-empty)))

(defn candidate-text
  [{:keys [displayId title descriptionExcerpt]}]
  (str/join " — " (remove str/blank? [displayId title descriptionExcerpt])))

(defn request-body
  [query candidates]
  {:state {:query query}
   :model model
   :questions
   {:match
    {:type "choice"
     :instructions "Which tracker candidate best matches the search query? Choose none when no candidate is a reasonable match."
     :criteria (into {"none" "No candidate reasonably matches the search query."}
                     (map-indexed (fn [index candidate]
                                    [(str "candidate-" index) (candidate-text candidate)]))
                     candidates)}}})

(defn system-one!
  [key body]
  (remote/request!
   :typesafe
   #(http/post api-url {:headers {"Authorization" (str "Bearer " key)
                                  "Content-Type" "application/json"}
                        :body (json/generate-string body)
                        :throw false})))

(defn valid-answer?
  [answer option-keys]
  (and (map? answer)
       (contains? option-keys (:choice answer))
       (number? (:confidence answer))
       (<= 0 (:confidence answer) 1)
       (map? (:probabilities answer))
       (= option-keys (set (map (comp name key) (:probabilities answer))))
       (every? (fn [[_ probability]]
                 (and (number? probability)
                      (<= 0 probability 1)))
               (:probabilities answer))))

(defn rerank
  [query candidates]
  (let [candidates (vec (take max-candidates candidates))
        key (api-key)]
    (when-not key
      (throw (ex-info "TYPESAFE_API_KEY is not configured." {:code :typesafe-api-key-missing})))
    (let [response (system-one! key (request-body query candidates))
          answer (get-in response [:answers :match])
          option-keys (conj (set (map #(str "candidate-" %) (range (count candidates)))) "none")]
      (when-not (valid-answer? answer option-keys)
        (throw (ex-info "TypeSafe AI returned an invalid Choice response." {:code :invalid-typesafe-response})))
      (when-not (= "none" (:choice answer))
        {:candidates
         (->> candidates
              (map-indexed (fn [index candidate]
                             (assoc candidate :semanticProbability
                                    (get (:probabilities answer) (keyword (str "candidate-" index))
                                         (get (:probabilities answer) (str "candidate-" index) 0)))))
              (sort-by (comp - :semanticProbability))
              vec)
         :ranking {:method "jev"
                   :model (:model response)
                   :confidence (:confidence answer)}}))))