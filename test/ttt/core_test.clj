(ns ttt.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [ttt.text.change-request :as change-request]
            [ttt.core :as core]
            [ttt.domain :as domain]))

(def config {:change-request {:body-begin-marker "<!-- ttt:begin -->" :body-end-marker "<!-- ttt:end -->" :section-title "Tracker"}})
(def scope (domain/scope-identity :linear "team-1"))
(def other-scope (domain/scope-identity :linear "team-2"))
(def project {:ref (domain/identity :linear :project "project-1") :display-id "reliability" :title "Reliability" :kind :project :url "https://linear/project" :scopes [scope]})
(def parent {:ref (domain/identity :linear :tracker-item "parent-1") :display-id "APP-100" :title "Parent" :url "https://linear/parent" :project {:ref (:ref project)} :scopes [scope]})
(def item {:ref (domain/identity :linear :tracker-item "issue-1") :display-id "APP-123" :title "Retry safely" :description "Existing description" :url "https://linear/item" :scopes [scope] :labels [{:ref (domain/identity :linear :label "existing") :display-id "Existing" :scopes []}]})
(def created-item {:ref (domain/identity :linear :tracker-item "issue-2") :display-id "APP-200" :title "Improve retry handling" :description "Created" :url "https://linear/created" :scopes [scope] :labels []})
(def bug-label {:ref (domain/identity :linear :label "bug") :display-id "Bug" :scopes [scope]})
(def change-request {:ref (domain/contained-identity :github :change-request "org/repo" 7) :display-id "org/repo#7" :title "Improve retry handling" :body "User body" :url "https://github.com/org/repo/pull/7"})
(def source {:branch "retry" :repository {:ref (domain/identity :github :repository "org/repo") :display-id "org/repo"} :change-request change-request})

(defn fake-runtime [calls overrides]
  (merge-with merge
              {:config config
               :forge {:inspect-current (fn [] source) :prefix-change-request-title (fn [id title] (str "[" id "] " title)) :update-change-request! (fn [& args] (swap! calls conj [:forge args])) :comment-change-request! (fn [& args] (swap! calls conj [:forge-comment args]))}
               :tracker {:configured-scope (fn [] scope) :resolve-item (fn [ref] (when (= ref "APP-123") item)) :resolve-parent-item (fn [ref] (when (= ref "APP-100") parent)) :resolve-project (fn [ref] (when (= ref "reliability") project)) :resolve-labels (fn [refs _] (if (= refs ["Bug"]) [bug-label] [])) :create-item! (fn [context intent] (swap! calls conj [:tracker-create context intent]) created-item) :update-item! (fn [resolved intent] (swap! calls conj [:tracker-update resolved intent]) resolved) :comment-item! (fn [& args] (swap! calls conj [:tracker-comment args]))}} overrides))

(deftest preview-is-neutral-and-read-only
  (let [calls (atom []) proposal (core/preview (fake-runtime calls {}) {:action :link-existing :item-ref "APP-123" :labels ["Bug"]})]
    (is (empty? @calls))
    (is (= ["existing" "bug"] (mapv #(get-in % [:ref :id]) (get-in proposal [:tracker-intent :labels]))))
    (is (not (str/includes? (pr-str proposal) "labelIds")))
    (is (not (str/includes? (pr-str proposal) "linearDescription")))))

(deftest scope-mismatches-fail-before-label-resolution-or-mutation
  (let [calls (atom []) runtime (fake-runtime calls {:tracker {:resolve-item (fn [_] (assoc item :scopes [other-scope])) :resolve-labels (fn [& _] (swap! calls conj :labels) [])}})]
    (is (thrown-with-msg? Exception #"outside the configured scope" (core/preview runtime {:action :link-existing :item-ref "wrong" :labels []})))
    (is (empty? @calls))))

(deftest parent-and-project-must-match
  (let [calls (atom []) runtime (fake-runtime calls {:tracker {:resolve-parent-item (fn [_] (assoc parent :project {:ref (domain/identity :linear :project "other")}))}})]
    (is (thrown-with-msg? Exception #"is not in project" (core/preview runtime {:action :create-new :parent-ref "APP-100" :project-ref "reliability"})))
    (is (empty? @calls))))

(deftest apply-updates-tracker-before-forge
  (let [calls (atom []) runtime (fake-runtime calls {}) proposal (core/preview runtime {:action :link-existing :item-ref "APP-123" :labels ["Bug"]})]
    (core/apply! runtime proposal)
    (is (= [:tracker-update :forge] (mapv first @calls)))))

(deftest comments-are-read-only-until-apply-and-route-to-one-provider
  (let [calls (atom [])
        runtime (fake-runtime calls {})
        item-proposal (core/preview runtime {:action :comment-item :item-ref "APP-123" :body "Tracker note"})]
    (is (empty? @calls))
    (is (= {:body "Tracker note"} (:comment item-proposal)))
    (core/apply! runtime item-proposal)
    (is (= [[:tracker-comment [item "Tracker note"]]] @calls))
    (reset! calls [])
    (let [forge-proposal (core/preview runtime {:action :comment-change-request :body "PR note"})]
      (is (empty? @calls))
      (core/apply! runtime forge-proposal)
      (is (= [[:forge-comment ["org/repo" "7" "PR note"]]] @calls)))))

(deftest change-request-updates-are-read-only-until-apply
  (let [calls (atom [])
        runtime (fake-runtime calls {})
        proposal (core/preview runtime {:action :update-change-request :body "Updated body"})]
    (is (empty? @calls))
    (is (= change-request (:change-request proposal)))
    (is (= {:body "Updated body"} (:change-request-update proposal)))
    (is (= "Updated body"
           (get-in (core/apply! runtime proposal) [:change-request :body])))
    (is (= [[:forge ["org/repo" "7" {:body "Updated body"}]]] @calls))))

(deftest change-request-updates-require-a-current-change-request
  (let [runtime (fake-runtime (atom []) {:forge {:inspect-current (fn [] (assoc source :change-request nil))}})]
    (is (thrown-with-msg? Exception #"No current change request found"
                          (core/preview runtime {:action :update-change-request :body "Updated body"})))))

(deftest change-request-comments-require-a-current-change-request
  (let [runtime (fake-runtime (atom []) {:forge {:inspect-current (fn [] (assoc source :change-request nil))}})]
    (is (thrown-with-msg? Exception #"No current change request found"
                          (core/preview runtime {:action :comment-change-request :body "Note"})))))

(deftest marker-identity-prevents-retargeting
  (let [calls (atom []) body (change-request/upsert-managed-section "User body" config created-item nil)
        runtime (fake-runtime calls {:forge {:inspect-current (fn [] (assoc-in source [:change-request :body] body))}})]
    (is (thrown-with-msg? Exception #"already linked to another tracker item" (core/preview runtime {:action :link-existing :item-ref "APP-123" :labels []})))
    (is (empty? @calls))))

(deftest project-context-body-links-by-name-and-url
  (let [calls (atom []) project-item (assoc item :project project)
        update (core/change-request-update (fake-runtime calls {}) source project-item nil)]
    (is (str/includes? (:body update) "- Project: [Reliability](https://linear/project)"))
    (is (not (str/includes? (:body update) "[reliability](")))
    (is (not (str/includes? (:body update) "]()")))))
