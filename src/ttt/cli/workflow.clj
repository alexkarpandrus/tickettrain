(ns ttt.cli.workflow
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [ttt.core :as core]
            [ttt.domain :as domain]
            [ttt.text.fuzzy :as fuzzy]
            [ttt.platform.git :as git]
            [ttt.cli.prompt :as prompt]
            [ttt.cli.ui :as ui]))

(defn print-progress [message] (println (ui/progress (str "⏳ " message))))

(defn abort! [] (throw (ex-info "Aborted." {:code :aborted})))
(defn print-change-request-summary [branch change-request]
  (println (ui/label "🌿 Current branch:") (ui/strong branch))
  (println (ui/label "🔀 Change request:") (format "%s %s" (:display-id change-request) (:title change-request)))
  (println (ui/label "🔗 URL:") (:url change-request)) (println))
(defn entity-heading [kind] (case kind :parent "🔎 Candidate parent items" :project "🔎 Candidate projects"))
(defn entity-label [kind] (case kind :parent "parent item" :project "project"))
(defn entity-title [item] (or (:title item) (:display-id item)))
(defn present-text [value] (let [text (some-> value str str/trim)] (when-not (str/blank? text) text)))
(defn entity-prefix [item] (when-let [display-id (present-text (:display-id item))] (str (ui/strong display-id) "  ")))
(defn choose-entity [items query options kind]
  (let [ranked (->> items (fuzzy/rank-issues query) (take (get-in options [:candidate-count] 5)) vec)]
    (when-not (seq ranked) (throw (ex-info (str "No " (entity-label kind) "s found for query: " query) {:query query :kind kind})))
    (println (ui/headline (entity-heading kind)))
    (doseq [[idx item] (map-indexed vector ranked)]
      (println (str "  " (ui/accent (format "%d." (inc idx))) " " (entity-prefix item) (entity-title item))))
    (println)
    (if (:yes options) (first ranked) (nth ranked (prompt/choose-index (count ranked) (entity-label kind))))))
(defn candidate-options [runtime options] (assoc options :candidate-count (get-in runtime [:config :search :candidate-count] 5)))
(defn choose-parent-item [runtime options project]
  (let [tracker (:tracker runtime) query (:parent options) exact ((:resolve-parent-item tracker) query)
        selected (if exact exact (do (print-progress "Searching tracker parent items...")
                                     (choose-entity (cond->> ((:search-parent-items tracker)) project (filter #(domain/in-project? % project))) query (candidate-options runtime options) :parent)))]
    (core/assert-entity-scope! runtime selected :parent)
    (when project (core/assert-parent-project! selected project)) selected))
(defn choose-project [runtime options]
  (let [tracker (:tracker runtime) query (:project options) exact ((:resolve-project tracker) query) scope (core/configured-scope runtime)
        selected (if exact exact (do (print-progress "Searching tracker projects...")
                                     (choose-entity (filter #(domain/entity-in-scope? % scope) ((:search-projects tracker))) query (candidate-options runtime options) :project)))]
    (core/assert-entity-scope! runtime selected :project)))
(defn selected-context [runtime options]
  (if-let [project (when (:project options) (choose-project runtime options))]
    (if (:parent options)
      (let [parent-item (choose-parent-item runtime options project)]
        {:kind :parent :prompt-label "Create tracker item and update the change request?" :selection-label "Selected parent"
         :selection-title (:display-id parent-item) :selection-subtitle (:title parent-item) :scope-label "Selected project"
         :scope-title (entity-title project) :scope-subtitle (:display-id project) :context {:parent parent-item :project project}})
      {:kind :project :prompt-label "Create tracker item and update the change request?" :selection-label "Selected project"
       :selection-title (:display-id project) :selection-subtitle (:title project) :context {:project project}})
    (let [parent-item (choose-parent-item runtime options nil)]
      {:kind :parent :prompt-label "Create tracker item and update the change request?" :selection-label "Selected parent"
       :selection-title (:display-id parent-item) :selection-subtitle (:title parent-item) :context {:parent parent-item}})))
(defn final-title [options source] (or (:title options) (:title source)))
(defn existing-branch-item [runtime branch]
  (when-let [display-id (or (git/branch-ticket-id branch) (git/branch-item-identifier branch))]
    (when-let [item ((get-in runtime [:tracker :resolve-item]) display-id)] (core/assert-entity-scope! runtime item :item))))
(defn assert-resume-context! [item {:keys [parent project]}]
  (let [actual-parent (get-in item [:parent :ref]) actual-project (get-in item [:project :ref])]
    (when (and parent (not (and actual-parent (domain/same-identity? actual-parent (:ref parent)))))
      (throw (ex-info "The resumed tracker item does not belong to the selected parent." {:code :resume-context-mismatch :item (:ref item) :parent (:ref parent)})))
    (when (and project (not (and actual-project (domain/same-identity? actual-project (:ref project)))))
      (throw (ex-info "The resumed tracker item does not belong to the selected project." {:code :resume-context-mismatch :item (:ref item) :project (:ref project)})))) item)
(defn print-resume-context [item]
  (when-let [parent (get-in item [:parent :display-id])] (println (ui/label "↳ Resumed parent:") (ui/strong parent)))
  (when-let [project (get-in item [:project :display-id])] (println (ui/label "↳ Resumed project:") (ui/strong project))))
(defn draft-change-request [options] {:title (or (:title options) (git/ensure-draft-title (git/last-commit-subject))) :body (git/last-commit-body)})
(defn print-preview [selection source title description]
  (println (ui/label (str "🧭 " (:selection-label selection) ":")) (str (ui/strong (:selection-title selection)) (when-let [subtitle (present-text (:selection-subtitle selection))] (str "  " subtitle))))
  (when-let [scope-label (:scope-label selection)] (println (ui/label (str "🗂 " scope-label ":")) (str (ui/strong (:scope-title selection)) (when-let [subtitle (present-text (:scope-subtitle selection))] (str "  " subtitle)))))
  (println (ui/label "🎫 Tracker title:") (ui/strong title))
  (when-let [source-title (:title source)] (println (ui/label "📝 Change request title:") source-title))
  (println (ui/headline "📄 Description preview")) (println (ui/muted "--------------------")) (println description) (println (ui/muted "--------------------")) (println))
(defn dry-run-payload [repository change-request title selection extra]
  (merge {:repository (:display-id repository) :change-request (some-> change-request :display-id) :title title}
         (case (:kind selection)
           :parent (cond-> {:parent (get-in selection [:context :parent :display-id])} (get-in selection [:context :project]) (assoc :project (get-in selection [:context :project :display-id])))
           :project {:project (get-in selection [:context :project :display-id])} {}) extra))
(defn execute-existing-change-request! [runtime options source]
  (let [selection (selected-context runtime options) change-request (:change-request source)
        request {:action :create-new :context (:context selection) :title (final-title options change-request) :labels []}
        proposal (core/preview runtime source request) intent (:tracker-intent proposal) prompt? (not (:yes options))]
    (print-change-request-summary (:branch source) change-request) (print-preview selection change-request (:title intent) (:description intent))
    (when (and prompt? (not (prompt/confirm? (:prompt-label selection)))) (abort!))
    (if (:dry-run options)
      (do (println (ui/warning "Dry run. No changes were made.")) (pprint/pprint (dry-run-payload (:repository source) change-request (:title intent) selection {:mode :existing-change-request})))
      (do (print-progress "Creating the tracker item...")
          (let [proposal* (core/preview runtime (core/inspect runtime) request)
                {:keys [item change-request-update]} (core/apply! runtime proposal*)]
            (println (ui/success "✅ Created tracker item:") (str (ui/strong (:display-id item)) " " (:url item)))
            (println (ui/success "✅ Updated change request:") (str (:display-id change-request) " " (:title change-request-update))))))))
(defn execute-no-change-request! [runtime options source]
  (let [draft (draft-change-request options) selection (selected-context runtime options) title (final-title options draft)
        description (core/base-item-description (:config runtime) draft) prompt? (not (:yes options))
        resume-item (when-let [item (existing-branch-item runtime (:branch source))] (assert-resume-context! item (:context selection))) repository (:repository source)]
    (println (ui/label "🌿 Current branch:") (ui/strong (:branch source)))
    (println (ui/warning "No open change request found for this branch. ttt will create one."))
    (when resume-item (println (ui/label "♻ Resuming with existing tracker item:") (str (ui/strong (:display-id resume-item)) (when-let [item-title (:title resume-item)] (str "  " item-title)))) (print-resume-context resume-item))
    (println) (print-preview selection draft title description)
    (when (and prompt? (not (prompt/confirm? "Create tracker item, rename the branch, and open a change request?"))) (abort!))
    (if (:dry-run options)
      (let [branch-preview (str "<item-key>-" (git/slugify title))]
        (println (ui/warning "Dry run. No changes were made."))
        (pprint/pprint (dry-run-payload repository nil title selection {:mode :create-change-request :current-branch (:branch source) :new-branch (if resume-item (:branch source) branch-preview) :base-branch (:default-target-branch repository)})))

      ;; ponytail: this sequence (create item -> rename branch -> push -> open PR ->
      ;; link back) is not atomic across Linear + GitHub. A crash leaves recoverable
      ;; partial state (orphan item, renamed branch). See "Recovery boundary" in
      ;; docs/integrations.md; add a local progress checkpoint if that proves insufficient.
      (let [item (or resume-item (do (print-progress "Creating the tracker item...") (core/create-item! runtime (:context selection) {:title title :description description :labels []})))
            new-branch (if resume-item (:branch source) (git/branch-name-for-item item))]
        (git/set-branch-ticket-id! new-branch (:display-id item))
        (when (not= (:branch source) new-branch) (print-progress (str "Renaming the branch to " new-branch "...")) (git/rename-branch! new-branch))
        (print-progress "Pushing the branch to origin...") (git/push-branch! new-branch)
        (let [change-request-update (core/change-request-update runtime {:change-request draft} item (:context selection))]
          (print-progress "Opening the change request...")
          (let [created ((get-in runtime [:forge :create-change-request!]) {:title (:title change-request-update) :body (:body change-request-update) :base (:default-target-branch repository) :head new-branch})
                change-request ((get-in runtime [:forge :identify-change-request]) repository created)
                created-source {:branch new-branch :repository repository :change-request change-request}
                link-proposal (core/preview runtime created-source {:action :link-existing :item item :labels []})]
            (print-progress "Linking the new change request back to the tracker...") (core/update-item! runtime item (:tracker-intent link-proposal))
            (println (ui/success (if resume-item "✅ Reused tracker item:" "✅ Created tracker item:")) (str (ui/strong (:display-id item)) " " (:url item)))
            (when (not= (:branch source) new-branch) (println (ui/success "✅ Renamed branch to:") (ui/strong new-branch)))
            (println (ui/success "✅ Opened change request:") (str (:display-id change-request) " " (:url change-request)))))))))

(defn execute! [runtime options]
  (core/ensure-selection-input! options)
  (let [source (core/inspect runtime)] (if (:change-request source) (execute-existing-change-request! runtime options source) (execute-no-change-request! runtime options source))))
