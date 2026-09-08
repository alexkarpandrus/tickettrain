(ns ttt.providers.tracker.state
  (:require [clojure.string :as str]
            [ttt.cli.prompt :as prompt]))

(defn match-target
  [configured available]
  (when-not (str/blank? (str configured))
    (first (filter #(or (= (str configured) (str (:id %)))
                        (= (str/lower-case (str configured))
                           (str/lower-case (str (:name %)))))
                   available))))

(defn resolve-target
  [provider configured available]
  (when-not (str/blank? (str configured))
    (or (match-target configured available)
        (throw (ex-info
                (str provider " target state not found: " configured
                     ". Available states: " (str/join ", " (map :name available)))
                {:code :target-state-not-found
                 :provider provider
                 :target-state configured
                 :available-states (mapv :name available)})))))

(defn choose-target
  [provider configured available]
  (or (match-target configured available)
      (when (seq available)
        (when-not (str/blank? (str configured))
          (println (str "Configured " provider " target state is unavailable: " configured)))
        (println "Target states:")
        (println (str "  1) " provider " default"))
        (doseq [[i state] (map-indexed vector available)]
          (println (str "  " (+ i 2) ") " (:name state))))
        (let [choice (prompt/choose-index (inc (count available)) "target state")]
          (when (pos? choice)
            (nth available (dec choice)))))))
