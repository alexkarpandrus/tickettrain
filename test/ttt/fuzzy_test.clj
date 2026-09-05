(ns ttt.fuzzy-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.text.fuzzy :as fuzzy]))

(deftest rank-issues-prefers-identifier-and-title-match
  (let [issues [{:identifier "OPS-10" :title "Data warehouse cleanup"}
                {:identifier "PAY-5" :title "Payments infrastructure rework"}
                {:identifier "WEB-3" :title "Landing page refresh"}]
        ranked (fuzzy/rank-issues "payments infra" issues)]
    (is (= "PAY-5" (:identifier (first ranked))))))

(deftest rank-issues-supports-neutral-display-ids
  (let [issues [{:display-id "PAY-5" :title "Payments infrastructure rework"}
                {:display-id "WEB-3" :title "Landing page refresh"}]]
    (is (= "PAY-5" (:display-id (first (fuzzy/rank-issues "PAY-5" issues)))))))

(deftest rank-issues-handles-mdps-and-separate-topics
  (let [issues [{:identifier "APP-303" :title "Automate allocation data publish into the sheet"}
                {:identifier "APP-324" :title "Split MDPs into separate topics per data type"}
                {:identifier "APP-392" :title "Add instrumentation to MDP"}]
        ranked (fuzzy/rank-issues "Split MDPs into separate topics" issues)]
    (is (= "APP-324" (:identifier (first ranked))))))
