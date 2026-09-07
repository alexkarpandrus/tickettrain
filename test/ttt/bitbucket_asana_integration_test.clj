(ns ttt.bitbucket-asana-integration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.adapters :as adapters]
            [ttt.core :as core]
            [ttt.domain :as domain]
            [ttt.providers.forge :as forge]
            [ttt.platform.shell :as shell]
            [ttt.providers.forge.bitbucket :as bitbucket]
            [ttt.providers.tracker :as tracker]
            [ttt.providers.tracker.asana :as asana]))

(def config
  {:tracker {:provider :asana :token "tok" :workspace "w"}
   :forge {:provider :bitbucket :email "alex@example.com" :api-token "token"}
   :change-request {:body-begin-marker "<!-- ttt:begin -->"
                    :body-end-marker "<!-- ttt:end -->"
                    :section-title "Asana"}})

(def asana-task
  {:gid "123" :name "Retry" :notes "Tracker description" :completed false
   :permalink_url "https://app.asana.com/0/0/123"
   :projects [{:gid "project-1" :name "App"
               :permalink_url "https://app.asana.com/0/project-1/list"}]
   :tags [{:gid "tag-old" :name "Old"}]})

(defn shell-stub
  [& args]
  (let [args (vec args)]
    (cond
      (= ["git" "rev-parse" "--abbrev-ref" "HEAD"] args) "retry"
      (= ["git" "remote" "get-url" "origin"] args) "git@bitbucket.org:team/repo.git"
      :else (throw (ex-info "Unexpected shell call" {:args args})))))

(defn bitbucket-stub
  [order payload]
  (fn [_ method path query]
    (cond
      (and (= :get method) (= "/repositories/team/repo" path))
      {:full_name "team/repo" :mainbranch {:name "main"}}

      (and (= :get method) (= "/repositories/team/repo/pullrequests" path))
      {:values [{:id 7 :title "Retry" :description "User body"
                 :links {:html {:href "https://bitbucket.org/team/repo/pull-requests/7"}}
                 :source {:branch {:name "retry"}} :destination {:branch {:name "main"}}}]}

      (and (= :put method) (= "/repositories/team/repo/pullrequests/7" path))
      (do (swap! order conj :forge) (reset! payload query) {})

      :else (throw (ex-info "Unexpected Bitbucket call" {:method method :path path})))))

(defn asana-stub
  [order payload]
  (fn [_ method path query]
    (cond
      (and (= :get method) (= "/tasks/123" path)) {:data asana-task}

      (and (= :put method) (= "/tasks/123" path))
      (do (swap! order conj :tracker) (reset! payload query) {:data asana-task})

      (and (= :post method) (= "/tasks/123/removeTag" path))
      (do (swap! order conj :tracker-remove-tag)
          (swap! payload assoc :removed-tag query)
          {:data {}})

      (and (= :post method) (= "/tasks/123/addTag" path))
      (do (swap! order conj :tracker-add-tag)
          (swap! payload assoc :added-tag query)
          {:data {}})

      (and (= :get method) (= "/workspaces/w/tags" path))
      {:data [{:gid "tag-1" :name "Bug"}]}

      (and (= :post method) (= "/tasks" path))
      (do (swap! order conj :tracker-create) (reset! payload query)
          {:data (assoc asana-task :gid "200")})

      :else (throw (ex-info "Unexpected Asana call" {:method method :path path})))))

(deftest bitbucket-asana-applies-link-intent-at-native-boundaries
  (let [runtime (adapters/runtime config forge/registry tracker/registry)
        order (atom [])
        asana-payload (atom nil)
        bitbucket-payload (atom nil)]
    (with-redefs [shell/run shell-stub
                  asana/api! (asana-stub order asana-payload)
                  bitbucket/api! (bitbucket-stub order bitbucket-payload)]
      (let [proposal (core/preview runtime {:action :link-existing :item-ref "123" :labels ["Bug"]})]
        (is (= :bitbucket (get-in proposal [:source :repository :ref :provider])))
        (is (= :asana (get-in proposal [:item :ref :provider])))
        (is (= "team/repo#7" (get-in proposal [:source :change-request :display-id])))
        (is (empty? @order))
        (core/apply! runtime proposal)))
    (is (= [:tracker :tracker-add-tag :forge] @order))
    (is (nil? (get-in @asana-payload [:data :tags])))
    (is (nil? (:removed-tag @asana-payload)))
    (is (= "tag-1" (get-in @asana-payload [:added-tag :data :tag])))
    (is (str/includes? (get-in @asana-payload [:data :notes]) "Tracker description"))
    (is (= "[123] Retry" (:title @bitbucket-payload)))
    (is (str/includes? (:description @bitbucket-payload)
                       "[App](https://app.asana.com/0/project-1/list)"))
    (is (str/includes? (:description @bitbucket-payload) "## Asana"))))

(deftest bitbucket-asana-translates-create-context-before-forge-update
  (let [runtime (adapters/runtime config forge/registry tracker/registry)
        order (atom [])
        asana-payload (atom nil)
        bitbucket-payload (atom nil)]
    (with-redefs [shell/run shell-stub
                  asana/api! (asana-stub order asana-payload)
                  bitbucket/api! (bitbucket-stub order bitbucket-payload)]
      (let [scope (domain/scope-identity :asana "w")
            project (asana/normalize-project scope {:gid "p1" :name "App"})
            proposal (core/preview runtime {:action :create-new :context {:project project} :labels ["Bug"]})]
        (core/apply! runtime proposal)))
    (is (= [:tracker-create :forge] @order))
    (is (= ["p1"] (get-in @asana-payload [:data :projects])))
    (is (= ["tag-1"] (get-in @asana-payload [:data :tags])))
    (is (= "[200] Retry" (:title @bitbucket-payload)))))
