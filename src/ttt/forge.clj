(ns ttt.forge
  (:require [ttt.forge.github :as github]))

(def registry
  {:github {:build github/neutral-adapter
            :validate-config! github/assert-ready!
            :setup github/setup}})
