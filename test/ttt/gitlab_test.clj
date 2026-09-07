(ns ttt.gitlab-test
  (:require [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.providers.forge.gitlab :as gitlab]))

(def config {:forge {:provider :gitlab :token "tok" :base-url "https://gitlab.com"}})

(deftest parses-common-remote-urls
  (is (= "group/sub/proj" (gitlab/parse-repo-slug "git@gitlab.com:group/sub/proj.git")))
  (is (= "group/proj" (gitlab/parse-repo-slug "https://gitlab.com/group/proj.git")))
  (is (= "group/proj" (gitlab/parse-repo-slug "ssh://git@gitlab.com/group/proj.git")))
  (is (= "group/proj" (gitlab/parse-repo-slug "ssh://git@gitlab.com:2222/group/proj.git"))))

(deftest prefix-change-request-title-adds-ticket-prefix
  (is (= "[APP-44] Title" (gitlab/prefix-change-request-title "APP-44" "Title"))))

(deftest prefix-change-request-title-replaces-existing-prefix
  (is (= "[APP-44] Title" (gitlab/prefix-change-request-title "APP-44" "[APP-1] Title"))))

(deftest normalizes-repo-and-change-request
  (let [repo (gitlab/normalize-repo {:path_with_namespace "group/proj" :default_branch "main"})
        cr (gitlab/normalize-change-request
            repo
            {:iid 7 :title "Retry" :description "Body"
             :web_url "https://gitlab.com/group/proj/-/merge_requests/7"
             :source_branch "retry" :target_branch "main"})]
    (is (= "group/proj" (:display-id repo)))
    (is (= "group/proj!7" (:display-id cr)))
    (is (= "Body" (:body cr)))
    (is (domain/same-identity?
         (domain/contained-identity :gitlab :change-request "group/proj" 7)
         (:ref cr)))))

(deftest setup-validates-the-current-project
  (let [calls (atom 0)]
    (with-redefs [gitlab/current-repo (fn [_]
                                        (swap! calls inc)
                                        {:display-id "group/repo"})]
      (with-out-str (gitlab/setup config)))
    (is (= 1 @calls))))

(deftest neutral-adapter-declares-every-forge-capability
  (let [adapter (gitlab/neutral-adapter config)]
    (is (= :gitlab (:provider adapter)))
    (is (= gitlab/capabilities (:capabilities adapter)))
    (is (every? #(fn? (get adapter %)) gitlab/capabilities))))

(deftest create-change-request-returns-the-raw-gitlab-response
  (let [response {:iid 1
                  :title "[KAN-2] Verify tickettrain GitLab workflow"
                  :description "Body"
                  :web_url "https://gitlab.example/group/project/-/merge_requests/1"
                  :source_branch "feature"
                  :target_branch "main"}
        request (atom nil)
        app-config {:token "test-token"}]
    (with-redefs [gitlab/remote-slug (constantly "group/project")
                  gitlab/api! (fn [config method path body]
                                (reset! request [config method path body])
                                response)]
      (is (= response
             (gitlab/create-change-request!
              app-config
              {:title "[KAN-2] Verify tickettrain GitLab workflow"
               :body "Body"
               :base "main"
               :head "feature"})))
      (is (= [app-config
              :post
              "/projects/group%2Fproject/merge_requests"
              {:source_branch "feature"
               :target_branch "main"
               :title "[KAN-2] Verify tickettrain GitLab workflow"
               :description "Body"}]
             @request)))))
