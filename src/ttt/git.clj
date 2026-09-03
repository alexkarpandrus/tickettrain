(ns ttt.git
  (:require [clojure.string :as str]
            [ttt.shell :as shell]))

(def merge-subject-patterns
  [#"(?i)^merge pull request\b"
   #"(?i)^merge branch\b"
   #"(?i)^merged? in\b"])

(defn merge-subject?
  [subject]
  (let [value (str/trim (or subject ""))]
    (boolean (some #(re-find % value) merge-subject-patterns))))

(defn recent-commits
  []
  (let [output (shell/run "git" "log" "-20" "--pretty=%s%x1f%b%x1e")]
    (->> (str/split output #"\u001e")
         (map str/trim)
         (remove str/blank?)
         (map (fn [entry]
                (let [[subject body] (str/split entry #"\u001f" 2)]
                  {:subject (str/trim (or subject ""))
                   :body (str/trim (or body ""))})))
         vec)))

(defn draft-commit
  []
  (let [commits (recent-commits)]
    (or (first (remove #(merge-subject? (:subject %)) commits))
        (first commits)
        {:subject "" :body ""})))

(defn last-commit-subject
  []
  (:subject (draft-commit)))

(defn last-commit-body
  []
  (:body (draft-commit)))

(defn ensure-draft-title
  [title]
  (let [value (str/trim (or title ""))]
    (when (str/blank? value)
      (throw (ex-info
              "Unable to derive a PR title from git history. Use --title or create a commit with a subject line."
              {})))
    value))

(defn slugify
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")
      (str/replace #"-{2,}" "-")))

(defn branch-name-for-item
  [item]
  (let [identifier (slugify (or (:display-id item) (:identifier item)))
        title-slug (slugify (:title item))]
    (if (str/blank? title-slug)
      identifier
      (str identifier "-" title-slug))))

(defn branch-name-for-issue
  [issue]
  (branch-name-for-item issue))

(def branch-item-pattern
  #"(?i)^([a-z]+-\d+)(?:-|$)")

(defn branch-item-identifier
  [branch]
  (some->> (re-find branch-item-pattern (or branch ""))
           second
           str/upper-case))

(defn branch-ticket-id
  [branch]
  (try
    (let [value (shell/run "git" "config" "--local" "--get"
                           (str "branch." branch ".ttt.ticket"))]
      (when-not (str/blank? value)
        value))
    (catch Exception ex
      (if (= 1 (:exit (ex-data ex)))
        nil
        (throw ex)))))

(defn set-branch-ticket-id!
  [branch ticket-id]
  (shell/run "git" "config" "--local"
             (str "branch." branch ".ttt.ticket")
             ticket-id))

(defn rename-branch!
  [new-name]
  (shell/run "git" "branch" "-m" new-name))

(defn push-branch!
  [branch]
  (shell/run "git" "push" "-u" "origin" branch))
