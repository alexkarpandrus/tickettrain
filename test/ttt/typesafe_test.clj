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