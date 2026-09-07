(ns ttt.providers.tracker
  (:require [ttt.providers.tracker.github-issues :as github-issues]
            [ttt.providers.tracker.jira :as jira]
            [ttt.providers.tracker.linear :as linear]))

(def registry
  {:linear {:build linear/neutral-adapter
            :validate-config! linear/assert-ready!
            :setup linear/setup}
   :github-issues {:build github-issues/neutral-adapter
                   :validate-config! github-issues/assert-ready!
                   :setup github-issues/setup}
   :jira {:build jira/neutral-adapter
          :validate-config! jira/assert-ready!
          :setup jira/setup}})
