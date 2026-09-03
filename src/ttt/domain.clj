(ns ttt.domain
  (:refer-clojure :exclude [identity]))

(defn identity
  [provider kind id]
  {:provider provider
   :kind kind
   :id (str id)})

(defn contained-identity
  [provider kind container id]
  (assoc (identity provider kind id) :container (str container)))

(defn identity-data
  [resource]
  (cond-> {:provider (name (:provider resource))
           :kind (name (:kind resource))
           :id (str (:id resource))}
    (:container resource) (assoc :container (str (:container resource)))))

(defn identity-key
  [resource]
  (let [{:keys [provider kind container id]} resource]
    (cond
      (and (= provider :github)
           (= kind :change-request)
           container)
      (str (name provider) ":" container "#" id)

      container
      (str (name provider) ":" (name kind) ":" container "#" id)

      :else
      (str (name provider) ":" (name kind) ":" id))))

(defn key-identity
  [key]
  (cond
    (re-matches #"^([^:]+):([^:]+):(.+)#([^#]+)$" key)
    (let [[_ provider kind container id]
          (re-matches #"^([^:]+):([^:]+):(.+)#([^#]+)$" key)]
      (contained-identity (keyword provider) (keyword kind) container id))

    (re-matches #"^([^:]+):(.+)#([^#]+)$" key)
    (let [[_ provider container id] (re-matches #"^([^:]+):(.+)#([^#]+)$" key)]
      (contained-identity (keyword provider) :change-request container id))

    :else
    (let [[_ provider kind id] (re-matches #"^([^:]+):([^:]+):(.+)$" key)]
      (when provider (identity (keyword provider) (keyword kind) id)))))

(defn same-identity?
  [left right]
  (= (identity-data left) (identity-data right)))

(defn scope-identity
  [provider id]
  (identity provider :scope id))

(defn entity-in-scope?
  [entity scope]
  (boolean (some #(same-identity? % scope) (:scopes entity))))

(defn display-id
  [entity]
  (or (:display-id entity) (:identifier entity)))

(defn in-project?
  [item project]
  (let [project-ref (get-in item [:project :ref])]
    (and project-ref (same-identity? project-ref (:ref project)))))
