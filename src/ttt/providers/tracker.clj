(ns ttt.providers.tracker
  (:require [ttt.config :as config]
            [ttt.providers.tracker.asana :as asana]
            [ttt.providers.tracker.github-issues :as github-issues]
            [ttt.providers.tracker.jira :as jira]
            [ttt.providers.tracker.linear :as linear]))

(def registry
  {:linear {:display-name "Linear"
            :setup-order 0
            :setup-settings (get config/provider-settings [:tracker :linear])
            :build linear/neutral-adapter
            :validate-config! linear/assert-ready!
            :setup linear/setup}
   :jira {:display-name "Jira"
          :setup-order 1
          :setup-settings (get config/provider-settings [:tracker :jira])
          :build jira/neutral-adapter
          :validate-config! jira/assert-ready!
          :setup jira/setup}
   :github-issues {:display-name "GitHub Issues"
                   :setup-order 2
                   :setup-settings (get config/provider-settings [:tracker :github-issues])
                   :build github-issues/neutral-adapter
                   :validate-config! github-issues/assert-ready!
                   :setup github-issues/setup}
   :asana {:display-name "Asana"
           :setup-order 3
           :setup-settings (get config/provider-settings [:tracker :asana])
           :build asana/neutral-adapter
           :validate-config! asana/assert-ready!
           :setup asana/setup}})
