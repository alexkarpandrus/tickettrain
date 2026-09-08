(ns ttt.tracker-state-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.cli.prompt :as prompt]
            [ttt.providers.tracker.state :as state]))

(def states [{:id "todo" :name "Todo"}
             {:id "doing" :name "In Progress"}])

(deftest configured-target-is-canonicalized-without-prompting
  (with-redefs [prompt/choose-index (fn [& _]
                                      (throw (ex-info "unexpected prompt" {})))]
    (is (= {:id "doing" :name "In Progress"}
           (state/choose-target "tracker" "in progress" states)))))

(deftest setup-can-select-a-state-or-the-provider-default
  (with-redefs [prompt/choose-index (fn [& _] 1)]
    (is (= {:id "todo" :name "Todo"}
           (state/choose-target "tracker" nil states))))
  (with-redefs [prompt/choose-index (fn [& _] 0)]
    (is (nil? (state/choose-target "tracker" nil states)))))

(deftest invalid-target-lists-available-states
  (is (thrown-with-msg?
       Exception
       #"Available states: Todo, In Progress"
       (state/resolve-target "tracker" "Review" states))))
