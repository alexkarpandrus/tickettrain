(ns ttt.providers.forge
  (:require [ttt.providers.forge.bitbucket :as bitbucket]
            [ttt.providers.forge.github :as github]
            [ttt.providers.forge.gitlab :as gitlab]))

(def registry
  {:github {:display-name "GitHub"
            :setup-order 0
            :setup-settings []
            :build github/neutral-adapter
            :validate-config! github/assert-ready!
            :setup github/setup}
   :gitlab {:display-name "GitLab"
            :setup-order 1
            :setup-settings [{:key :token :label "GitLab token" :env "GITLAB_TOKEN"
                              :required? true :secret? true}
                             {:key :base-url :env "GITLAB_BASE_URL"}]
            :build gitlab/neutral-adapter
            :validate-config! gitlab/assert-ready!
            :setup gitlab/setup}
   :bitbucket {:display-name "Bitbucket"
               :setup-order 2
               :setup-settings [{:key :email :label "Bitbucket email" :env "BITBUCKET_EMAIL"
                                 :required? true}
                                {:key :api-token :label "Bitbucket API token"
                                 :env "BITBUCKET_API_TOKEN" :required? true :secret? true}
                                {:key :base-url :env "BITBUCKET_BASE_URL"}]
               :build bitbucket/neutral-adapter
               :validate-config! bitbucket/assert-ready!
               :setup bitbucket/setup}})
