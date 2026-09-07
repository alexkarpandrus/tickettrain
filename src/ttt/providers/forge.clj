(ns ttt.providers.forge
  (:require [ttt.providers.forge.bitbucket :as bitbucket]
            [ttt.providers.forge.github :as github]
            [ttt.providers.forge.gitlab :as gitlab]))

(def registry
  {:github {:build github/neutral-adapter
            :validate-config! github/assert-ready!
            :setup github/setup}
   :gitlab {:build gitlab/neutral-adapter
            :validate-config! gitlab/assert-ready!
            :setup gitlab/setup}
   :bitbucket {:build bitbucket/neutral-adapter
               :validate-config! bitbucket/assert-ready!
               :setup bitbucket/setup}})
