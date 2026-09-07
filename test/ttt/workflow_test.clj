(ns ttt.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.domain :as domain]
            [ttt.platform.git :as git]
            [ttt.cli.prompt :as prompt]
            [ttt.cli.workflow :as workflow]))

(def scope (domain/scope-identity :linear "team-1"))
(def parent {:ref (domain/identity :linear :tracker-item "parent-1") :display-id "APP-100" :title "Parent" :url "https://linear/parent" :scopes [scope]})
(def item {:ref (domain/identity :linear :tracker-item "issue-1") :display-id "APP-200" :title "Retry" :description "Created" :url "https://linear/item" :scopes [scope] :parent {:ref (:ref parent) :display-id "APP-100"} :labels []})
(def repository {:ref (domain/identity :github :repository "org/repo") :display-id "org/repo" :default-target-branch "main"})
(def change-request {:ref (domain/contained-identity :github :change-request "org/repo" 7) :display-id "org/repo#7" :title "Retry" :body "Body" :url "https://github/pr"})
(def config {:change-request {:body-begin-marker "<!-- ttt:begin -->" :body-end-marker "<!-- ttt:end -->" :section-title "Tracker"} :search {:candidate-count 5}})
(defn runtime [calls source]
  {:config config :forge {:inspect-current (fn [] source) :prefix-change-request-title (fn [id title] (str "[" id "] " title)) :update-change-request! (fn [& _] (swap! calls conj :forge-update)) :create-change-request! (fn [intent] (swap! calls conj :forge-create) (assoc change-request :title (:title intent) :body (:body intent))) :identify-change-request (fn [_ created] created)}
   :tracker {:configured-scope (fn [] scope) :resolve-parent-item (fn [ref] (when (= ref "APP-100") parent)) :resolve-project (fn [_] nil) :search-parent-items (fn [] [parent]) :search-projects (fn [] []) :resolve-item (fn [ref] (when (= ref "APP-200") item)) :resolve-labels (fn [& _] []) :create-item! (fn [_ _] (swap! calls conj :tracker-create) item) :update-item! (fn [resolved _] (swap! calls conj :tracker-update) resolved)}})
(def existing-source {:branch "feature" :repository repository :change-request change-request})
(def no-change-request-source {:branch "feature" :repository repository :change-request nil})

(deftest existing-change-request-dry-run-is-side-effect-free
  (let [calls (atom []) output (with-out-str (workflow/execute-existing-change-request! (runtime calls existing-source) {:parent "APP-100" :yes true :dry-run true} existing-source))]
    (is (empty? @calls)) (is (str/includes? output "existing-change-request"))))
(deftest existing-change-request-uses-shared-core
  (let [calls (atom [])]
    (with-out-str (workflow/execute-existing-change-request! (runtime calls existing-source) {:parent "APP-100" :yes true} existing-source))
    (is (= [:tracker-create :forge-update] @calls))))
(deftest selected-context-rejects-an-exact-parent-outside-scope
  (let [calls (atom []) runtime* (assoc-in (runtime calls existing-source) [:tracker :resolve-parent-item] (fn [_] (assoc parent :scopes [(domain/scope-identity :linear "team-2")])))]
    (is (thrown-with-msg? Exception #"outside the configured scope" (workflow/selected-context runtime* {:parent "APP-999" :yes true})))
    (is (empty? @calls))))

(deftest selected-context-allows-no-parent-or-project
  (let [calls (atom [])]
    (is (= {:kind :none
            :prompt-label "Create tracker item and update the change request?"
            :selection-label "Selected scope"
            :selection-title "None"
            :context {}}
           (workflow/selected-context (runtime calls existing-source) {})))
    (is (empty? @calls))))

(deftest preserves-confirmation-before-mutation
  (let [calls (atom []) confirmed (atom nil)]
    (with-redefs [prompt/confirm? (fn [message] (reset! confirmed message) true)]
      (with-out-str (workflow/execute-existing-change-request! (runtime calls existing-source) {:parent "APP-100"} existing-source)))
    (is (= "Create tracker item and update the change request?" @confirmed))
    (is (= [:tracker-create :forge-update] @calls))))
(deftest no-change-request-dry-run-is-side-effect-free
  (let [calls (atom []) output (with-redefs [workflow/draft-change-request (fn [_] {:title "Retry" :body "Body"}) git/branch-ticket-id (fn [_] nil) git/branch-item-identifier (fn [_] nil)]
                                (with-out-str (workflow/execute-no-change-request! (runtime calls no-change-request-source) {:parent "APP-100" :yes true :dry-run true} no-change-request-source)))]
    (is (empty? @calls)) (is (str/includes? output "create-change-request"))))

(deftest no-change-request-apply-mutates-in-order
  (let [calls (atom [])]
    (with-redefs [workflow/draft-change-request (fn [_] {:title "Retry" :body "Body"})
                  git/branch-ticket-id (fn [_] nil)
                  git/branch-item-identifier (fn [_] nil)
                  git/set-branch-ticket-id! (fn [branch id] (swap! calls conj [:set-ticket branch id]))
                  git/rename-branch! (fn [branch] (swap! calls conj [:rename branch]))
                  git/push-branch! (fn [branch] (swap! calls conj [:push branch]))]
      (with-out-str (workflow/execute-no-change-request! (runtime calls no-change-request-source) {:parent "APP-100" :yes true} no-change-request-source)))
    (is (= [:tracker-create
            [:set-ticket "app-200-retry" "APP-200"]
            [:rename "app-200-retry"]
            [:push "app-200-retry"]
            :forge-create
            :tracker-update]
           @calls))))
