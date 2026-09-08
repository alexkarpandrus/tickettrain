(ns ttt.providers.forge
  (:require [ttt.config :as config]
            [ttt.providers.forge.bitbucket :as bitbucket]
            [ttt.providers.forge.github :as github]
            [ttt.providers.forge.gitlab :as gitlab]))

(def registry
  {:github {:display-name "GitHub"
            :setup-order 0
            :setup-settings (get config/provider-settings [:forge :github])
            :build github/neutral-adapter
            :validate-config! github/assert-ready!
            :setup github/setup}
   :gitlab {:display-name "GitLab"
            :setup-order 1
            :setup-settings (get config/provider-settings [:forge :gitlab])
            :build gitlab/neutral-adapter
            :validate-config! gitlab/assert-ready!
            :setup gitlab/setup}
   :bitbucket {:display-name "Bitbucket"
               :setup-order 2
               :setup-settings (get config/provider-settings [:forge :bitbucket])
               :build bitbucket/neutral-adapter
               :validate-config! bitbucket/assert-ready!
               :setup bitbucket/setup}})
