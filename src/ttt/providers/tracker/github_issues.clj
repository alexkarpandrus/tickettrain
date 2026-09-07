(ns ttt.providers.tracker.github-issues
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]))

(def issue-fields "number,title,body,url,labels,state,milestone")

(defn gh-json
  [& args]
  (json/parse-string (apply shell/run "gh" args) true))

(defn repo-slug
  ([] (repo-slug (System/getenv "GH_REPO")))
  ([repository]
   (or (some-> repository str/trim not-empty)
       (:nameWithOwner (gh-json "repo" "view" "--json" "nameWithOwner")))))

(defn normalize-label
  [scope name]
  {:ref (domain/identity :github-issues :label name)
   :display-id name
   :scopes [scope]})

(defn normalize-milestone
  [scope milestone]
  (let [title (or (:title milestone) (str (:number milestone)))]
    {:ref (domain/identity :github-issues :project title)
     :display-id title
     :title title
     :url (:url milestone)
     :scopes [scope]}))

(defn normalize-item
  [scope issue]
  (when issue
    {:ref (domain/identity :github-issues :tracker-item (:number issue))
     :display-id (str "#" (:number issue))
     :number (:number issue)
     :title (:title issue)
     :description (:body issue)
     :url (:url issue)
     :state (when (:state issue) {:name (:state issue)})
     :scopes [scope]
     :project (when-let [m (:milestone issue)] (normalize-milestone scope m))
     :labels (mapv #(normalize-label scope (:name %)) (or (:labels issue) []))}))

(defn issue-by-number
  [scope number]
  (try
    (normalize-item scope (gh-json "issue" "view" (str number) "--json" issue-fields))
    (catch Exception _ nil)))

(defn list-issues
  ([scope limit] (list-issues scope nil limit))
  ([scope query limit]
   (let [base ["issue" "list" "--state" "all" "--json" issue-fields "--limit" (str limit)]
         args (cond-> base (seq query) (into ["--search" query]))]
     (mapv #(normalize-item scope %) (apply gh-json args)))))

(defn parse-number
  [ref]
  (some->> (re-find #"^#?(\d+)$" (str/trim (or ref ""))) second Long/parseLong))

(defn resolve-item
  [scope ref]
  (let [ref (str/trim (or ref ""))]
    (if-let [number (parse-number ref)]
      (issue-by-number scope number)
      (first (list-issues scope ref 5)))))

(defn milestones
  [scope]
  (mapv #(normalize-milestone scope %) (gh-json "api" (str "repos/" (repo-slug) "/milestones"))))

(defn resolve-project
  [scope ref]
  (let [ref (str/trim (or ref ""))]
    (first (filter #(or (= (str/lower-case (or (:display-id %) "")) (str/lower-case ref))
                        (= (str/lower-case (or (:title %) "")) (str/lower-case ref)))
                   (milestones scope)))))

(defn labels
  [scope]
  (mapv #(normalize-label scope (:name %))
        (gh-json "label" "list" "--json" "name,color,description" "--limit" "100")))

(defn resolve-labels
  [scope label-refs _scope]
  (mapv #(normalize-label scope %) (distinct (or label-refs []))))

(defn label-names
  [labels]
  (mapv #(or (get-in % [:ref :id]) (:display-id %)) labels))

(defn project-name
  [project]
  (or (get-in project [:ref :id]) (:display-id project)))

(defn unsupported-parent!
  [& _]
  (throw (ex-info "GitHub Issues does not support parent issues; use --project (milestone) instead."
                  {:code :unsupported-parent})))

(defn create-item!
  [scope context title description labels]
  (let [milestone (some-> (:project context) project-name)
        label-args (mapcat (fn [label] ["--label" label]) (label-names labels))
        args (cond-> ["issue" "create" "--title" title "--body" description]
               milestone (into ["--milestone" milestone])
               (seq label-args) (into label-args))
        url (apply shell/run "gh" args)
        number (some->> (re-find #"/issues/(\d+)$" url) second Long/parseLong)]
    (issue-by-number scope number)))

(defn update-item!
  [scope item description labels]
  (let [number (:number item)
        label-args (mapcat (fn [label] ["--add-label" label]) (label-names labels))
        args (cond-> ["issue" "edit" (str number) "--body" description]
               (seq label-args) (into label-args))]
    (apply shell/run "gh" args)
    (issue-by-number scope number)))

(defn create-item-from-intent!
  [scope context {:keys [title description labels]}]
  (when (:parent context)
    (unsupported-parent!))
  (create-item! scope context title description labels))

(defn update-item-from-intent!
  [scope item {:keys [description labels]}]
  (update-item! scope item description labels))

(defn assert-ready!
  [_app-config]
  (try
    (shell/run "gh" "auth" "status")
    (catch Exception _
      (throw (ex-info "GitHub Issues tracker requires an authenticated `gh` CLI session."
                      {:code :provider-config-invalid})))))

(defn setup
  [_app-config]
  (assert-ready! nil)
  (println "GitHub Issues tracker: gh authenticated")
  nil)

(def capabilities
  #{:configured-scope
    :search-parent-items
    :resolve-parent-item
    :resolve-item
    :search-projects
    :resolve-project
    :search-labels
    :resolve-labels
    :create-item!
    :update-item!})

(defn neutral-adapter
  [_app-config]
  (let [scope* (delay (domain/scope-identity :github-issues (repo-slug)))]
    {:provider :github-issues
     :capabilities capabilities
     :configured-scope (fn [] @scope*)
     :search-parent-items #(list-issues @scope* 100)
     :resolve-parent-item unsupported-parent!
     :resolve-item #(resolve-item @scope* %)
     :search-projects #(milestones @scope*)
     :resolve-project #(resolve-project @scope* %)
     :search-labels #(labels @scope*)
     :resolve-labels #(resolve-labels @scope* %1 %2)
     :create-item! #(create-item-from-intent! @scope* %1 %2)
     :update-item! #(update-item-from-intent! @scope* %1 %2)}))
