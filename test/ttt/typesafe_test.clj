(ns ttt.typesafe-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.inference.typesafe :as typesafe]))

(def candidates
  [{:displayId "APP-1" :title "Refresh landing page" :descriptionExcerpt "Marketing site"}
   {:displayId "APP-2" :title "Retry failed payments" :descriptionExcerpt "Recover transient Stripe failures"}])

(deftest rerank-uses-jev-choice-probabilities
  (with-redefs [typesafe/api-key (constantly "secret")
                typesafe/system-one! (fn [key body]
                                       (is (= "secret" key))
                                       (is (= "jev-latest" (:model body)))
                                       (is (= "APP-2 — Retry failed payments — Recover transient Stripe failures"
                                              (get-in body [:questions :match :criteria "candidate-1"])))
                                       {:model "jev-1.13.0"
                                        :answers {:match {:type "choice"
                                                          :choice "candidate-1"
                                                          :confidence 0.9
                                                          :probabilities {:candidate-0 0.05
                                                                          :candidate-1 0.9
                                                                          :none 0.05}}}})]
    (let [result (typesafe/rerank "Stripe retries" candidates)]
      (is (= ["APP-2" "APP-1"] (mapv :displayId (:candidates result))))
      (is (= 0.9 (get-in result [:candidates 0 :semanticProbability])))
      (is (= {:method "jev" :model "jev-1.13.0" :confidence 0.9}
             (:ranking result))))))

(deftest rerank-rejects-malformed-api-responses
  (with-redefs [typesafe/api-key (constantly "secret")
                typesafe/system-one! (fn [_ _] {:answers {:match {:choice "invented"}}})]
    (is (thrown-with-msg? Exception #"invalid Choice response"
                          (typesafe/rerank "retry" candidates)))))


(defn project-response [relation]
  {:answers {:project-relation
             {:type "choice" :choice relation :confidence 0.9
              :probabilities (into {} (map (fn [option] [option (if (= relation option) 0.9 0.05)])
                                          ["same" "different" "unspecified"]))}}})

(deftest project-check-uses-raw-text-and-all-three-relations
  (doseq [[note relation] [["I archived invoices in Atlas" "different"]
                          ["I archived invoices in Beacon" "same"]
                          ["I archived invoices" "unspecified"]
                          ["I answered a teammate" "unspecified"]]]
    (with-redefs [typesafe/api-key (constantly "test-key")
                  typesafe/system-one! (fn [_ body]
                                        (is (= {:note note :project "Beacon"} (:state body)))
                                        (is (= "jev-latest" (:model body)))
                                        (is (= "choice" (get-in body [:questions :project-relation :type])))
                                        (is (= #{"same" "different" "unspecified"}
                                               (set (keys (get-in body [:questions :project-relation :criteria])))))
                                        (project-response relation))]
      (is (= {:relation relation :confidence 0.9} (typesafe/check-project note "Beacon"))))))

(deftest project-check-fails-without-credentials-or-a-valid-answer
  (with-redefs [typesafe/api-key (constantly nil)
                typesafe/system-one! (fn [& _] (throw (Exception. "Must not call Jev")))]
    (is (thrown-with-msg? Exception #"TYPESAFE_API_KEY is not configured"
                          (typesafe/check-project "A note" "Beacon"))))
  (let [valid (project-response "same")
        answer-path [:answers :project-relation]
        malformed (concat [nil "invalid JSON" [] {} {:answers {}}
                           (update-in valid answer-path dissoc :type)
                           (assoc-in valid (conj answer-path :type) "score")
                           (assoc-in valid (conj answer-path :choice) "unknown")]
                          (map #(assoc-in valid (conj answer-path :confidence) %)
                               [nil "0.9" -0.1 1.1 Double/NaN Double/POSITIVE_INFINITY])
                          (map #(assoc-in valid (conj answer-path :probabilities) %)
                               [nil [] {:same 1} {:same 1 :different 0 :unspecified 0 :unknown 0}
                                {:same "one" :different 0 :unspecified 0}
                                {:same -0.1 :different 0.9 :unspecified 0.2}]))]
    (doseq [response malformed]
      (with-redefs [typesafe/api-key (constantly "test-key")
                    typesafe/system-one! (fn [& _] response)]
        (is (thrown-with-msg? Exception #"invalid project Choice response"
                              (typesafe/check-project "A note" "Beacon")))))))