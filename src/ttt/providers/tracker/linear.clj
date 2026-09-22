(ns ttt.providers.tracker.linear
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ttt.cli.prompt :as prompt]
            [ttt.config :as config]
            [ttt.domain :as domain]
            [ttt.platform.remote :as remote]
            [ttt.providers.tracker.pagination :as pagination]
            [ttt.providers.tracker.state :as state]))

(def endpoint "https://api.linear.app/graphql")

(def issue-summary-fragment
  "id identifier title url state { id name type }")

(def issue-fragment
  (str issue-summary-fragment
       " description priority dueDate"
       " project { id name slugId url } team { id key name }"
       " parent { id identifier title url }"
       " labels { nodes { id name color team { id key name } } }"
       " inverseRelations { nodes { id type issue { " issue-summary-fragment " } } }"))

(def parent-issues-query
  (str "query ParentIssues($teamId: String!, $first: Int!, $after: String) {"
       "  team(id: $teamId) { id name key"
       "    issues(first: $first, after: $after) {"
       "      nodes { " issue-fragment " }"
       "      pageInfo { hasNextPage endCursor } } } }"))

(def projects-query
  "query Projects($first: Int!, $after: String) {
     projects(first: $first, after: $after) {
       nodes {
         id
         name
         description
         url
         slugId
         teams { nodes { id key name } }
       }
       pageInfo { hasNextPage endCursor }
     }
   }")

(def issue-labels-query
  "query IssueLabels($first: Int!, $after: String) {
     issueLabels(first: $first, after: $after) {
       nodes {
         id
         name
         description
         color
         isGroup
         team { id key name }
         parent { id name }
       }
       pageInfo { hasNextPage endCursor }
     }
   }")

(def issue-by-identifier-query
  (str "query IssueByIdentifier($issueId: String!) {"
       "  issue(id: $issueId) { " issue-fragment " } }"))

(def issue-relations-query
  (str "query IssueRelations($issueId: String!, $first: Int!, $after: String) {"
       "  issue(id: $issueId) {"
       "    inverseRelations(first: $first, after: $after) {"
       "      nodes { id type issue { " issue-summary-fragment " } }"
       "      pageInfo { hasNextPage endCursor } } } }"))

(def viewer-query
  "query Viewer { viewer { id name email organization { urlKey } } }")

(def teams-query
  "query Teams { teams { nodes { id name key } } }")

(def team-states-query
  "query TeamStates($teamId: String!) {
     team(id: $teamId) {
       id
       name
       states { nodes { id name type position } }
     }
   }")

(def create-issue-mutation
  (str "mutation CreateIssue($input: IssueCreateInput!) {"
       "  issueCreate(input: $input) { success issue { " issue-fragment " } } }"))

(def update-issue-mutation
  (str "mutation UpdateIssue($id: String!, $input: IssueUpdateInput!) {"
       "  issueUpdate(id: $id, input: $input) { success issue { " issue-fragment " } } }"))


(def create-issue-relation-mutation
  "mutation CreateIssueRelation($input: IssueRelationCreateInput!) {
     issueRelationCreate(input: $input) { success issueRelation { id } }
   }")

(def delete-issue-relation-mutation
  "mutation DeleteIssueRelation($id: String!) {
     issueRelationDelete(id: $id) { success }
   }")

(def create-comment-mutation
  "mutation CreateComment($input: CommentCreateInput!) {
     commentCreate(input: $input) { success comment { id body url } }
   }")

(defn tracker-config
  [app-config]
  (config/tracker-settings app-config))

(defn graphql!
  [app-config query variables]
  (let [body (remote/request!
              :linear
              #(http/post endpoint
                          {:headers {"Authorization" (:api-key (tracker-config app-config))
                                     "Content-Type" "application/json"}
                           :body (json/generate-string {:query query
                                                        :variables variables})
                           :throw false}))]
    (when-let [errors (seq (:errors body))]
      (let [detail (:message (first errors))]
        (throw (ex-info (str "Linear GraphQL error: " detail)
                        {:code :remote-api-error
                         :provider :linear
                         :detail detail
                         :errors errors}))))
    (:data body)))

(defn paginate
  ([app-config query variables page-path]
   (paginate app-config query variables page-path nil))
  ([app-config query variables page-path limit]
   (let [page-size (if limit (min 100 limit) 100)]
     (loop [after nil
            acc []]
       (let [response (graphql! app-config query (merge variables {:first page-size :after after}))
             page-info (get-in response (conj page-path :pageInfo))
             items (get-in response (conj page-path :nodes))
             next-acc (into acc (or items []))]
         (cond
           (not (:hasNextPage page-info)) next-acc
           (and limit (>= (count next-acc) limit)) (take limit next-acc)
           (:endCursor page-info) (recur (:endCursor page-info) next-acc)
           :else next-acc))))))

(defn parent-items
  [app-config]
  (let [team-id (:team-id (tracker-config app-config))
        limit (get-in app-config [:search :parent-fetch-limit] 100)]
    (paginate app-config parent-issues-query {:teamId team-id} [:team :issues] limit)))

(defn normalize-project
  [project]
  (let [teams (get-in project [:teams :nodes])]
    {:ref (domain/identity :linear :project (:id project))
     :display-id (or (:slugId project) (:name project))
     :title (:name project)
     :kind :project
     :url (:url project)
     :scopes (mapv #(domain/scope-identity :linear (:id %)) teams)}))

(defn normalize-label
  [label]
  (let [team (:team label)]
    {:ref (domain/identity :linear :label (:id label))
     :display-id (:name label)
     :scopes (if team
               [(domain/scope-identity :linear (:id team))]
               [])}))

(def neutral-priority-by-native
  {0 "none" 1 "urgent" 2 "high" 3 "medium" 4 "low"})

(def native-priority-by-neutral
  {"none" 0 "urgent" 1 "high" 2 "medium" 3 "low" 4})

(defn normalize-state
  [state]
  (case (:type state)
    "started" "active"
    "completed" "completed"
    "canceled" "canceled"
    (when state "open")))

(defn normalize-priority
  [priority]
  (get neutral-priority-by-native (some-> priority int)))

(defn normalize-due-at
  [due-date]
  (when due-date (str due-date "T00:00:00Z")))

(defn blocker-relations
  [item]
  (filter #(= "blocks" (:type %)) (get-in item [:inverseRelations :nodes])))

(defn normalize-item-summary
  [item]
  (when item
    {:ref (domain/identity :linear :tracker-item (:id item))
     :display-id (:identifier item)
     :title (:title item)
     :url (:url item)
     :state (normalize-state (:state item))}))

(defn normalize-item
  [item]
  (when item
    (let [team (:team item)]
      {:ref (domain/identity :linear :tracker-item (:id item))
       :display-id (:identifier item)
       :title (:title item)
       :description (:description item)
       :url (:url item)
       :state (normalize-state (:state item))
       :provider-state (:state item)
       :priority (normalize-priority (:priority item))
       :due-at (normalize-due-at (:dueDate item))
       :blocked-by (mapv #(normalize-item-summary (:issue %))
                         (blocker-relations item))
       :scopes (if team
                 [(domain/scope-identity :linear (:id team))]
                 [])
       :project (some-> (:project item) normalize-project)
       :parent (some-> (:parent item) normalize-item-summary)
       :labels (mapv normalize-label (get-in item [:labels :nodes]))})))

(defn exact-match?
  [left right]
  (= (str/lower-case (str/trim (or left "")))
     (str/lower-case (str/trim (or right "")))))

(defn projects
  [app-config]
  (let [limit (get-in app-config [:search :project-fetch-limit] 100)]
    (map #(assoc % :title (:name %) :kind :project)
         (paginate app-config projects-query {} [:projects] limit))))

(defn labels
  [app-config]
  (remove :isGroup (paginate app-config issue-labels-query {} [:issueLabels] 100)))

(defn normalized-parent-items
  [app-config]
  (mapv normalize-item (parent-items app-config)))


(defn list-items
  [app-config matches? limit]
  (let [team-id (:team-id (tracker-config app-config))]
    (pagination/collect-matches
     (fn [after]
       (let [response (graphql! app-config parent-issues-query
                                {:teamId team-id :first 100 :after after})
             page (get-in response [:team :issues])
             page-info (:pageInfo page)]
         {:items (mapv normalize-item (:nodes page))
          :next-cursor (when (:hasNextPage page-info) (:endCursor page-info))}))
     matches?
     limit)))

(defn normalized-projects
  [app-config]
  (mapv normalize-project (projects app-config)))

(defn normalized-labels
  [app-config]
  (mapv normalize-label (labels app-config)))

(defn normalized-project-by-ref
  [app-config project-ref]
  (->> (normalized-projects app-config)
       (filter #(or (exact-match? (get-in % [:ref :id]) project-ref)
                    (exact-match? (:display-id %) project-ref)
                    (exact-match? (:title %) project-ref)))
       first))

(defn item-by-identifier
  [app-config item-id]
  (when (re-matches #"(?i)(?:[a-z][a-z0-9]*-\d+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})"
                    (str/trim item-id))
    (get (graphql! app-config
                   issue-by-identifier-query
                   {:issueId item-id})
         :issue)))

(defn normalized-item-by-identifier
  [app-config item-id]
  (some-> (item-by-identifier app-config item-id) normalize-item))

(defn normalized-label-compatible-with-scope?
  [label scope]
  (or (empty? (:scopes label))
      (domain/entity-in-scope? label scope)))

(defn normalized-label-by-ref
  [app-config label-ref scope]
  (let [matches (->> (normalized-labels app-config)
                     (filter #(normalized-label-compatible-with-scope? % scope))
                     (filter #(or (exact-match? (get-in % [:ref :id]) label-ref)
                                  (exact-match? (:display-id %) label-ref)))
                     vec)]
    (case (count matches)
      0 nil
      1 (first matches)
      (throw (ex-info (str "Linear label reference is ambiguous: " label-ref)
                      {:code :ambiguous-label
                       :label label-ref
                       :matches (mapv #(select-keys % [:ref :display-id]) matches)})))))

(defn resolve-normalized-labels
  [app-config label-refs scope]
  (mapv (fn [label-ref]
          (or (normalized-label-by-ref app-config label-ref scope)
              (throw (ex-info (str "Linear label not found: " label-ref)
                              {:code :label-not-found
                               :label label-ref}))))
        (distinct (or label-refs []))))

(defn viewer
  [app-config]
  (get (graphql! app-config viewer-query {}) :viewer))

(defn assignee-id
  [app-config]
  (let [configured (:assignee-id (tracker-config app-config))]
    (cond
      (nil? configured) nil
      (str/blank? (str configured)) nil
      (= "self" (str/lower-case (str configured))) (:id (viewer app-config))
      :else configured)))

(defn team-states
  [app-config team-id]
  (get-in (graphql! app-config
                    team-states-query
                    {:teamId team-id})
          [:team :states :nodes]))


(defn provider-id
  [entity]
  (if (map? entity)
    (or (get-in entity [:ref :id]) (:id entity))
    entity))

(defn state-id
  [app-config team-id]
  (let [{:keys [state-id state-name target-state]} (tracker-config app-config)]
    (cond
      (not (str/blank? (str target-state)))
      (:id (state/resolve-target "Linear" target-state (team-states app-config team-id)))

      (and state-id (not (str/blank? (str state-id))))
      state-id

      (not (str/blank? (str state-name)))
      (:id (state/resolve-target "Linear" state-name (team-states app-config team-id)))

      :else nil)))

(def neutral-state-types
  {"open" ["unstarted" "backlog" "triage"]
   "active" ["started"]
   "completed" ["completed"]
   "canceled" ["canceled"]})

(defn neutral-state-id
  [app-config team-id neutral-state]
  (let [states (team-states app-config team-id)
        candidate (some (fn [state-type]
                          (->> states
                               (filter #(= state-type (:type %)))
                               (sort-by :position)
                               first))
                        (get neutral-state-types neutral-state))]
    (or (:id candidate)
        (throw (ex-info (str "Linear cannot represent neutral state: " neutral-state)
                        {:code :unsupported-work-item-value
                         :provider :linear
                         :field :state
                         :value neutral-state})))))

(defn native-due-date
  [due-at]
  (when due-at
    (str (.toLocalDate (.atZone (java.time.Instant/parse due-at)
                                java.time.ZoneOffset/UTC)))))

(defn native-work-item-input
  [app-config item intent]
  (let [team-id (or (get-in item [:scopes 0 :id])
                    (:team-id (tracker-config app-config)))]
    (cond-> {}
      (and (contains? intent :state)
           (not= (:state intent) (:state item)))
      (assoc :stateId (neutral-state-id app-config team-id (:state intent)))

      (contains? intent :priority)
      (assoc :priority (get native-priority-by-neutral (or (:priority intent) "none")))

      (contains? intent :due-at)
      (assoc :dueDate (native-due-date (:due-at intent))))))

(defn validate-work-item-intent!
  [app-config _action item intent]
  (native-work-item-input app-config item intent)
  (when (and item
             (some #(= (provider-id item) (provider-id %)) (:blocked-by intent)))
    (throw (ex-info "A Linear issue cannot block itself."
                    {:code :invalid-blocker
                     :provider :linear
                     :item (provider-id item)})))
  intent)

(defn create-item!
  ([app-config context title description label-ids]
   (create-item! app-config context title description label-ids {}))
  ([app-config {:keys [parent project]} title description label-ids native-input]
   (let [team-id (or (get-in parent [:team :id])
                     (:team-id (tracker-config app-config)))
         assignee (assignee-id app-config)
         state (state-id app-config team-id)
         input (merge
                (cond-> {:teamId team-id
                         :title title
                         :description description}
                  parent (assoc :parentId (:id parent))
                  project (assoc :projectId (:id project))
                  (seq label-ids) (assoc :labelIds (vec label-ids))
                  assignee (assoc :assigneeId assignee)
                  state (assoc :stateId state))
                native-input)
         response (graphql! app-config create-issue-mutation {:input input})
         item (get-in response [:issueCreate :issue])
         success? (get-in response [:issueCreate :success])]
     (when-not success?
       (throw (ex-info "Linear issueCreate returned success=false" {})))
     item)))

(defn update-item!
  [app-config item-id input]
  (let [response (graphql! app-config
                           update-issue-mutation
                           {:id item-id
                            :input input})
        success? (get-in response [:issueUpdate :success])]
    (when-not success?
      (throw (ex-info "Linear issueUpdate returned success=false" {})))
    (get-in response [:issueUpdate :issue])))


(defn create-blocker-relation!
  [app-config item-id blocker-id]
  (let [response (graphql! app-config create-issue-relation-mutation
                           {:input {:issueId blocker-id
                                    :relatedIssueId item-id
                                    :type "blocks"}})]
    (when-not (get-in response [:issueRelationCreate :success])
      (throw (ex-info "Linear issueRelationCreate returned success=false" {})))))

(defn delete-blocker-relation!
  [app-config relation-id]
  (let [response (graphql! app-config delete-issue-relation-mutation
                           {:id relation-id})]
    (when-not (get-in response [:issueRelationDelete :success])
      (throw (ex-info "Linear issueRelationDelete returned success=false" {})))))

(defn configured-scope
  [app-config]
  (domain/scope-identity :linear (:team-id (tracker-config app-config))))

(defn assert-ready!
  [app-config]
  (config/assert-settings! :linear
                           (tracker-config app-config)
                           [:api-key :team-id :workspace-url]))




(defn sync-blockers!
  [app-config item-id blockers]
  (let [relations (paginate app-config issue-relations-query
                            {:issueId item-id}
                            [:issue :inverseRelations])
        existing (into {} (comp (filter #(= "blocks" (:type %)))
                                (map (juxt #(get-in % [:issue :id]) :id)))
                       relations)
        desired (set (map provider-id blockers))]
    (doseq [blocker-id (remove #(contains? existing %) desired)]
      (create-blocker-relation! app-config item-id blocker-id))
    (doseq [[blocker-id relation-id] existing
            :when (not (contains? desired blocker-id))]
      (delete-blocker-relation! app-config relation-id))))

(defn comment-item!
  [app-config item body]
  (let [response (graphql! app-config create-comment-mutation
                           {:input {:issueId (provider-id item) :body body}})]
    (when-not (get-in response [:commentCreate :success])
      (throw (ex-info "Linear commentCreate returned success=false" {})))
    nil))

(defn label-ids
  [labels]
  (mapv provider-id labels))

(defn provider-context
  [app-config {:keys [parent project]}]
  (cond-> {}
    parent (assoc :parent {:id (provider-id parent)
                           :team {:id (:team-id (tracker-config app-config))}})
    project (assoc :project {:id (provider-id project)})))

(defn create-item-from-intent!
  [app-config context {:keys [title description labels] :as intent}]
  (let [native-input (native-work-item-input app-config nil intent)
        created (create-item! app-config
                              (provider-context app-config context)
                              title
                              description
                              (label-ids labels)
                              native-input)]
    (if (contains? intent :blocked-by)
      (try
        (sync-blockers! app-config (:id created) (:blocked-by intent))
        (or (some-> (item-by-identifier app-config (:id created)) normalize-item)
            (throw (ex-info "Linear issue refresh returned no item." {})))
        (catch Exception ex
          (throw (ex-info
                  (str "Linear created " (:identifier created)
                       " but could not apply blockers or refresh it. Inspect the issue before retrying.")
                  (assoc (or (ex-data ex) {})
                         :created-item (:identifier created)
                         :preserve-created-item true)
                  ex))))
      (normalize-item created))))

(defn update-item-from-intent!
  [app-config item {:keys [description labels] :as intent}]
  (let [item-id (provider-id item)
        native-input (native-work-item-input app-config item intent)
        input (cond-> native-input
                (not= description (:description item)) (assoc :description description)
                (not= (label-ids labels) (label-ids (:labels item)))
                (assoc :labelIds (label-ids labels)))
        updated (when (seq input) (update-item! app-config item-id input))]
    (if (contains? intent :blocked-by)
      (do
        (sync-blockers! app-config item-id (:blocked-by intent))
        (some-> (item-by-identifier app-config item-id) normalize-item))
      (or (some-> updated normalize-item) item))))

(defn valid-config-value?
  [v]
  (and (some? v)
       (not (config/placeholder? v))
       (not (str/blank? (str v)))))


(defn teams
  [app-config]
  (get-in (graphql! app-config teams-query {}) [:teams :nodes]))

(defn pick-team
  [app-config]
  (let [available (teams app-config)
        configured (get-in app-config [:tracker :team-id])]
    (cond
      (and (valid-config-value? configured)
           (some #(= configured (:id %)) available))
      (first (filter #(= configured (:id %)) available))

      (empty? available)
      (throw (ex-info "No Linear teams found for this API key." {:code :setup-no-teams}))

      :else
      (do
        (println "Teams:")
        (doseq [[i team] (map-indexed vector available)]
          (println (str "  " (inc i) ") " (:name team) " (" (:key team) ")")))
        (nth available (prompt/choose-index (count available) "team" 0))))))

(defn pick-state-config
  [app-config team]
  (let [available (vec (team-states app-config (:id team)))
        {:keys [state-id state-name target-state]} (tracker-config app-config)
        configured (or target-state state-id state-name)
        selected (state/choose-target "Linear" configured available)]
    {:target-state (some-> selected :name)
     :state-id nil
     :state-name nil}))

(defn setup
  [app-config]
  (let [viewer (get (graphql! app-config viewer-query {}) :viewer)
        team (pick-team app-config)
        url-key (get-in viewer [:organization :urlKey])
        state-config (pick-state-config app-config team)]
    (println (str "Linear user: " (:email viewer)))
    (println (str "Workspace: https://linear.app/" url-key))
    (println (str "Team: " (:name team) " (" (:key team) ")"))
    {:tracker (merge {:team-id (:id team)
                      :workspace-url (str "https://linear.app/" url-key)
                      :assignee-id "self"}
                     state-config)}))

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
  {:provider :linear
   :capabilities capabilities
   :item-capabilities #{:item-lifecycle :item-priority :item-due-dates :item-blockers}
   :configured-scope #(configured-scope app-config)
   :list-items #(list-items app-config %1 %2)
   :search-parent-items #(normalized-parent-items app-config)
   :resolve-parent-item #(normalized-item-by-identifier app-config %)
   :resolve-item #(normalized-item-by-identifier app-config %)
   :search-projects #(normalized-projects app-config)
   :resolve-project #(normalized-project-by-ref app-config %)
   :search-labels #(normalized-labels app-config)
   :resolve-labels #(resolve-normalized-labels app-config %1 %2)
   :validate-work-item-intent! #(validate-work-item-intent! app-config %1 %2 %3)
   :create-item! #(create-item-from-intent! app-config %1 %2)
   :comment-item! #(comment-item! app-config %1 %2)
   :update-item! #(update-item-from-intent! app-config %1 %2)})
