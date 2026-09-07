(ns ttt.bitbucket-test
  (:require [babashka.http-client :as http]
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.providers.forge.bitbucket :as bitbucket]))

(def config {:forge {:provider :bitbucket :email "alex@example.com" :api-token "token"}})

(deftest parses-common-remote-urls
  (is (= "team/repo" (bitbucket/parse-repo-slug "git@bitbucket.org:team/repo.git")))
  (is (= "team/repo" (bitbucket/parse-repo-slug "https://user@bitbucket.org/team/repo.git")))
  (is (= "team/repo" (bitbucket/parse-repo-slug "ssh://git@bitbucket.org/team/repo.git"))))

(deftest prefix-change-request-title-adds-ticket-prefix
  (is (= "[APP-44] Title" (bitbucket/prefix-change-request-title "APP-44" "Title"))))

(deftest normalizes-repo-and-change-request
  (let [repo (bitbucket/normalize-repo {:full_name "team/repo" :mainbranch {:name "main"}})
        cr (bitbucket/normalize-change-request
            repo
            {:id 7 :title "Retry" :description "Body"
             :links {:html {:href "https://bitbucket.org/team/repo/pull-requests/7"}}
             :source {:branch {:name "retry"}} :destination {:branch {:name "main"}}})]
    (is (= "team/repo" (:display-id repo)))
    (is (= "team/repo#7" (:display-id cr)))
    (is (= "Body" (:body cr)))
    (is (= "main" (:target-branch cr)))
    (is (domain/same-identity?
         (domain/contained-identity :bitbucket :change-request "team/repo" 7)
         (:ref cr)))))

(deftest api-token-authentication-uses-account-email
  (let [authorization (atom nil)]
    (with-redefs [http/get (fn [_ opts]
                             (reset! authorization (get-in opts [:headers "Authorization"]))
                             {:status 200 :body "{}"})]
      (bitbucket/api! config :get "/repositories/team/repo" nil))
    (is (= "alex@example.com:token"
           (String. (.decode (java.util.Base64/getDecoder) (subs @authorization 6)) "UTF-8")))))

(deftest setup-validates-the-current-repository
  (let [calls (atom 0)]
    (with-redefs [bitbucket/current-repo (fn [_]
                                          (swap! calls inc)
                                          {:display-id "team/repo"})]
      (with-out-str (bitbucket/setup config)))
    (is (= 1 @calls))))

(deftest neutral-adapter-declares-every-forge-capability
  (let [adapter (bitbucket/neutral-adapter config)]
    (is (= :bitbucket (:provider adapter)))
    (is (= bitbucket/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) bitbucket/capabilities))))
