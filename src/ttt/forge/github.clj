(ns ttt.forge.github
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ttt.domain :as domain]
            [ttt.shell :as shell]))

(def change-request-fields
  "number,title,body,url,headRefName,baseRefName")

(def repo-fields
  "nameWithOwner,defaultBranchRef")

(defn normalize-change-request
  ([change-request]
   {:ref (domain/identity :github :change-request (:number change-request))
    :display-id (str "#" (:number change-request))
    :number (:number change-request)
    :title (:title change-request)
    :body (:body change-request)
    :url (:url change-request)
    :source-branch (or (:source-branch change-request)
                       (:headRefName change-request))
    :target-branch (or (:target-branch change-request)
                       (:baseRefName change-request))})
  ([repo change-request]
   (assoc (normalize-change-request change-request)
          :ref (domain/contained-identity :github
                                          :change-request
                                          (:display-id repo)
                                          (:number change-request))
          :display-id (str (:display-id repo) "#" (:number change-request)))))

(defn normalize-repo
  [repo]
  (let [slug (or (:nameWithOwner repo) (:slug repo))]
    {:ref (domain/identity :github :repository slug)
     :display-id slug
     :slug slug
     :default-target-branch (or (get-in repo [:defaultBranchRef :name])
                                (:default-target-branch repo))}))

(defn connectivity-error?
  [text]
  (let [value (str/lower-case (or text ""))]
    (boolean
     (some #(str/includes? value %)
           ["error connecting to api.github.com"
            "could not resolve host"
            "dial tcp"
            "tls handshake timeout"
            "i/o timeout"
            "connection refused"
            "connection reset by peer"]))))

(defn maybe-current-change-request
  []
  (try
    (-> (shell/run "gh" "pr" "view" "--json" change-request-fields)
        (json/parse-string true)
        normalize-change-request)
    (catch Exception ex
      (let [data (ex-data ex)
            text (str/lower-case (str (:err data) " " (:out data)))]
        (if (str/includes? text "no pull requests found")
          nil
          (if (connectivity-error? text)
            (throw (ex-info
                    "Unable to reach GitHub via gh while checking the current pull request."
                    {:kind :github-connectivity}
                    ex))
            (throw ex)))))))

(defn current-change-request
  []
  (try
    (or (maybe-current-change-request)
        (throw (ex-info "No current change request found." {})))
    (catch Exception ex
      (throw (ex-info
              "Unable to resolve a pull request for the current branch. Run this inside a git repo with an open PR and a configured GitHub CLI session."
              {}
              ex)))))

(defn current-repo
  []
  (try
    (-> (shell/run "gh" "repo" "view" "--json" repo-fields)
        (json/parse-string true)
        normalize-repo)
    (catch Exception ex
      (throw (ex-info
              "Unable to resolve the current GitHub repository via gh."
              {}
              ex)))))

(defn current-branch
  []
  (try
    (shell/run "git" "rev-parse" "--abbrev-ref" "HEAD")
    (catch Exception ex
      (throw (ex-info
              "Unable to resolve the current git branch. Run this inside a checked out git repository."
              {}
              ex)))))

(defn prefix-change-request-title
  [item-id title]
  (let [clean-title (-> (or title "")
                        str/trim
                        (str/replace #"^\[[^]]+\]\s*" "")
                        (str/replace #"^[A-Z]+-\d+[:\s-]*" ""))]
    (str "[" item-id "] " clean-title)))

(defn update-change-request!
  [repo-slug change-request-number {:keys [body title]}]
  (let [tmp-file (java.io.File/createTempFile "ttt-pr-body" ".json")]
    (spit tmp-file (json/generate-string (cond-> {}
                                          body (assoc :body body)
                                          title (assoc :title title))))
    (try
      (try
        (shell/run "gh" "api"
                   (str "repos/" repo-slug "/pulls/" change-request-number)
                   "--method" "PATCH"
                   "--input" (.getAbsolutePath tmp-file))
        (catch Exception ex
          (throw (ex-info
                  (str "Unable to update GitHub PR #" change-request-number ".")
                  {:repo repo-slug
                   :pr-number change-request-number}
                  ex))))
      (finally
        (io/delete-file tmp-file true)))))

(defn create-change-request!
  [{:keys [title body base head]}]
  (let [tmp-file (java.io.File/createTempFile "ttt-pr-create" ".md")]
    (spit tmp-file (or body ""))
    (try
      (try
        (let [url (shell/run "gh" "pr" "create"
                             "--title" title
                             "--body-file" (.getAbsolutePath tmp-file)
                             "--base" base
                             "--head" head)
              number (some->> (re-find #"/pull/(\d+)" url)
                              second
                              Long/parseLong)]
          {:number number
           :title title
           :body body
           :url url
           :source-branch head
           :target-branch base})
        (catch Exception ex
          (let [text (str (:err (ex-data ex)) " " (:out (ex-data ex)))]
            (throw (ex-info
                    (if (connectivity-error? text)
                      "Unable to create a GitHub pull request because gh could not reach GitHub."
                      "Unable to create a GitHub pull request.")
                    {:base base
                     :head head
                     :title title
                     :kind (when (connectivity-error? text)
                             :github-connectivity)}
                    ex)))))
      (finally
        (io/delete-file tmp-file true)))))

(defn identify-change-request
  [repo change-request]
  (normalize-change-request repo change-request))

(defn inspect-current
  []
  (let [repository (normalize-repo (current-repo))
        change-request (maybe-current-change-request)]
    {:branch (current-branch)
     :repository repository
     :change-request (some->> change-request
                              (normalize-change-request repository))}))

(defn assert-ready!
  [_app-config])

(def capabilities
  #{:current-branch
    :maybe-current-change-request
    :current-change-request
    :current-repo
    :inspect-current
    :identify-change-request
    :update-change-request!
    :create-change-request!
    :prefix-change-request-title})

(defn neutral-adapter
  [_app-config]
  {:provider :github
   :capabilities capabilities
   :current-branch current-branch
   :maybe-current-change-request maybe-current-change-request
   :current-change-request current-change-request
   :current-repo current-repo
   :inspect-current inspect-current
   :identify-change-request identify-change-request
   :update-change-request! update-change-request!
   :create-change-request! create-change-request!
   :prefix-change-request-title prefix-change-request-title})