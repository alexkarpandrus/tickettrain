(ns ttt.providers.tracker.github-issues
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.platform.shell :as shell]
            [ttt.providers.tracker.state :as state]))

(def issue-fields "number,title,body,url,labels,state,stateReason,milestone")

(def target-states [{:id "open" :name "open"}
                    {:id "closed" :name "closed"}])

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
     :state (case (:state issue)
              "OPEN" "open"
              "CLOSED" (if (= "NOT_PLANNED" (:stateReason issue)) "canceled" "completed")
              nil)
     :scopes [scope]
     :project (when-let [m (:milestone issue)] (normalize-milestone scope m))
     :labels (mapv #(normalize-label scope (:name %)) (or (:labels issue) []))}))

(defn issue-by-number
  [scope number]
  (try
    (normalize-item scope (gh-json "issue" "view" (str number)
                                   "--repo" (:id scope)
                                   "--json" issue-fields))
    (catch Exception _ nil)))

(defn list-issues
  ([scope limit] (list-issues scope nil limit))
  ([scope query limit]
   (let [base ["issue" "list" "--repo" (:id scope) "--state" "all" "--json" issue-fields "--limit" (str limit)]
         args (cond-> base (seq query) (into ["--search" query]))]
     (mapv #(normalize-item scope %) (apply gh-json args)))))


(defn list-items
  [scope matches? limit]
  ;; ponytail: gh owns cursor pagination; widen its limit until enough items match or it is exhausted.
  (loop [fetch-limit (max 100 limit)]
    (let [items (list-issues scope fetch-limit)
          matched (vec (take limit (filter matches? items)))]
      (if (or (= limit (count matched))
              (< (count items) fetch-limit))
        matched
        (recur (* 2 fetch-limit))))))

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
  (mapv #(normalize-milestone scope %) (gh-json "api" (str "repos/" (:id scope) "/milestones"))))

(defn resolve-project
  [scope ref]
  (let [ref (str/trim (or ref ""))]
    (first (filter #(or (= (str/lower-case (or (:display-id %) "")) (str/lower-case ref))
                        (= (str/lower-case (or (:title %) "")) (str/lower-case ref)))
                   (milestones scope)))))

(defn labels
  [scope]
  (mapv #(normalize-label scope (:name %))
        (gh-json "label" "list" "--repo" (:id scope)
                 "--json" "name,color,description" "--limit" "100")))

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
  [app-config scope context title description labels]
  (let [target (state/resolve-target "GitHub Issues"
                                     (get-in app-config [:tracker :target-state])
                                     target-states)
        milestone (some-> (:project context) project-name)
        label-args (mapcat (fn [label] ["--label" label]) (label-names labels))
        args (cond-> ["issue" "create" "--repo" (:id scope) "--title" title "--body" description]
               milestone (into ["--milestone" milestone])
               (seq label-args) (into label-args))
        url (apply shell/run "gh" args)
        number (some->> (re-find #"/issues/(\d+)$" url) second Long/parseLong)]
    (when (= "closed" (:id target))
      (shell/run "gh" "issue" "close" (str number) "--repo" (:id scope)))
    (issue-by-number scope number)))

(defn update-item!
  [scope item description labels]
  (let [number (:number item)
        current (set (label-names (:labels item)))
        desired (set (label-names labels))
        add-args (mapcat (fn [label] ["--add-label" label])
                         (remove current (label-names labels)))
        remove-args (mapcat (fn [label] ["--remove-label" label])
                            (remove desired (label-names (:labels item))))
        args (cond-> ["issue" "edit" (str number) "--repo" (:id scope) "--body" description]
               (seq add-args) (into add-args)
               (seq remove-args) (into remove-args))]
    (apply shell/run "gh" args)
    (issue-by-number scope number)))

(defn comment-item!
  [scope item body]
  (shell/run "gh" "issue" "comment" (str (:number item))
             "--repo" (:id scope)
             "--body" body)
  nil)

(defn create-item-from-intent!
  [app-config scope context {:keys [title description labels]}]
  (when (:parent context)
    (unsupported-parent!))
  (create-item! app-config scope context title description labels))

(defn update-item-from-intent!
  [scope item {:keys [description labels]}]
  (update-item! scope item description labels))

(defn assert-ready!
  [_app-config]
  (try
    (shell/run "gh" "auth" "status")
    (catch Exception _
      (throw (ex-info "GitHub Issues tracker requires an authenticated `gh` CLI session. Run `gh auth login`, then re-run `ttt setup`."
                      {:code :provider-config-invalid})))))

(defn setup
  [app-config]
  (assert-ready! app-config)
  (println "GitHub Issues tracker: gh authenticated")
  (let [repository (repo-slug (get-in app-config [:tracker :repository]))
        target (state/choose-target "GitHub Issues"
                                    (get-in app-config [:tracker :target-state])
                                    target-states)]
    {:tracker {:repository repository
               :target-state (some-> target :name)}}))

(def capabilities
  #{:configured-scope
    :list-items
    :search-parent-items
    :resolve-parent-item
    :resolve-item
    :search-projects
    :resolve-project
    :search-labels
    :resolve-labels
    :create-item!
    :comment-item!
    :update-item!})

(defn neutral-adapter
  [app-config]
  (let [scope* (delay (domain/scope-identity :github-issues (repo-slug (get-in app-config [:tracker :repository]))))]
    {:provider :github-issues
     :capabilities capabilities
     :item-capabilities #{}
     :configured-scope (fn [] @scope*)
     :list-items #(list-items @scope* %1 %2)
     :search-parent-items #(list-issues @scope* 100)
     :resolve-parent-item unsupported-parent!
     :resolve-item #(resolve-item @scope* %)
     :search-projects #(milestones @scope*)
     :resolve-project #(resolve-project @scope* %)
     :search-labels #(labels @scope*)
     :resolve-labels #(resolve-labels @scope* %1 %2)
     :create-item! #(create-item-from-intent! app-config @scope* %1 %2)
     :comment-item! #(comment-item! @scope* %1 %2)
     :update-item! #(update-item-from-intent! @scope* %1 %2)}))
