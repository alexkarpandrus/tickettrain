(ns ttt.providers.tracker
  (:require [ttt.providers.tracker.linear :as linear]))

(def registry
  {:linear {:build linear/neutral-adapter
            :validate-config! linear/assert-ready!
            :setup linear/setup}})
