(ns ttt.providers.forge
  (:require [ttt.providers.forge.github :as github]))

(def registry
  {:github {:build github/neutral-adapter
            :validate-config! github/assert-ready!
            :setup github/setup}})
