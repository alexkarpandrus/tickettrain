(ns ttt.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.cli.ui :as ui]))

(deftest style-is-plain-when-colors-are-disabled
  (with-redefs [ui/color-enabled? (fn [] false)]
    (is (= "hello" (ui/style "hello" :green :bold)))))

(deftest style-wraps-text-when-colors-are-enabled
  (with-redefs [ui/color-enabled? (fn [] true)]
    (let [styled (ui/style "hello" :green :bold)]
      (is (str/includes? styled "\u001b[32m"))
      (is (str/includes? styled "\u001b[1m"))
      (is (str/includes? styled "hello"))
      (is (str/includes? styled "\u001b[0m")))))
