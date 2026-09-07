(ns ttt.remote-test
  (:require [clojure.test :refer [deftest is testing]]
            [ttt.cli.agent :as agent]
            [ttt.platform.remote :as remote]
            [ttt.providers.forge.bitbucket :as bitbucket]
            [ttt.providers.forge.gitlab :as gitlab]
            [ttt.providers.tracker.asana :as asana]
            [ttt.providers.tracker.jira :as jira]
            [ttt.providers.tracker.linear :as linear]))

(defn thrown
  [f]
  (try
    (f)
    nil
    (catch Exception ex ex)))

(deftest reports-common-provider-error-shapes
  (doseq [[provider status body expected]
          [[:gitlab 403 "{\"message\":\"Forbidden\"}" "Forbidden"]
           [:bitbucket 403 "{\"error\":{\"message\":\"Access denied\"}}" "Access denied"]
           [:jira 400 "{\"errorMessages\":[\"Invalid request\"],\"errors\":{\"project\":\"Project is required\"}}"
            "Invalid request; project: Project is required"]
           [:asana 402 "{\"errors\":[{\"message\":\"Premium feature\"}]}" "Premium feature"]
           [:linear 429 "{\"errors\":[{\"message\":\"Rate limit exceeded\"}]}" "Rate limit exceeded"]]]
    (testing (name provider)
      (let [ex (thrown #(remote/request! provider (constantly {:status status :body body})))
            data (ex-data ex)]
        (is (= :remote-api-error (:code data)))
        (is (= provider (:provider data)))
        (is (= status (:status data)))
        (is (= expected (:detail data)))
        (is (.contains (.getMessage ex) expected))))))

(deftest reports-transport-errors-without-exposing-request-data
  (let [ex (thrown #(remote/request! :asana (fn [] (throw (Exception. "Connection timed out")))))
        data (ex-data ex)]
    (is (= :provider-unavailable (:code data)))
    (is (= :asana (:provider data)))
    (is (= "Connection timed out" (:detail data)))))

(deftest redacts-and-bounds-provider-details
  (is (= "token=<redacted>" (remote/sanitize-detail "token=secret-value")))
  (is (= remote/max-detail-length
         (count (remote/sanitize-detail (apply str (repeat 600 "x")))))))

(deftest http-adapters-identify-the-provider
  (let [seen (atom [])
        calls [#(gitlab/api! {:forge {:token "token"}} :get "/user" nil)
               #(bitbucket/api! {:forge {:email "a@example.com" :api-token "token"}} :get "/user" nil)
               #(jira/api! {:tracker {:email "a@example.com" :api-token "token" :site-url "https://example.atlassian.net"}} :get "/myself" nil)
               #(asana/api! {:tracker {:token "token"}} :get "/users/me" nil)
               #(linear/graphql! {:tracker {:api-key "token"}} "query { viewer { id } }" {})]]
    (with-redefs [remote/request! (fn [provider _] (swap! seen conj provider) {})]
      (doseq [call calls] (call)))
    (is (= [:gitlab :bitbucket :jira :asana :linear] @seen))))

(deftest json-failure-preserves-nested-provider-details
  (let [cause (thrown #(remote/request! :jira
                                        (constantly {:status 403
                                                     :body "{\"errorMessages\":[\"Missing permission\"]}"})))
        error (:error (agent/failure "search" (ex-info "Search failed." {} cause)))]
    (is (= "remote-api-error" (:code error)))
    (is (= "jira" (:provider error)))
    (is (= 403 (:status error)))
    (is (= "Missing permission" (:details error)))))

(deftest json-failure-preserves-github-cli-errors
  (let [cause (ex-info "gh failed"
                       {:command "gh pr create"
                        :exit 1
                        :err "GraphQL: Resource not accessible by integration"})
        error (:error (agent/failure "apply" (ex-info "Unable to create a GitHub pull request." {} cause)))]
    (is (= "provider-command-failed" (:code error)))
    (is (= "github" (:provider error)))
    (is (= "GraphQL: Resource not accessible by integration" (:details error)))))
