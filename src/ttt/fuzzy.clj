(ns ttt.fuzzy
  (:require [babashka.deps :as deps]
            [clojure.set :as set]
            [clojure.string :as str]
            [ttt.domain :as domain]))

(deps/add-deps
 {:deps {(symbol "clj-fuzzy/clj-fuzzy") {:mvn/version "0.4.1"}}})

(def jaro-winkler
  (requiring-resolve 'clj-fuzzy.metrics/jaro-winkler))

(def dice
  (requiring-resolve 'clj-fuzzy.metrics/dice))

(defn normalize
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(defn tokens
  [s]
  (->> (str/split (normalize s) #"\s+")
       (remove str/blank?)
       set))

(defn singularize-token
  [token]
  (cond
    (str/ends-with? token "ies")
    (str (subs token 0 (- (count token) 3)) "y")

    (and (str/ends-with? token "es")
         (> (count token) 3))
    (subs token 0 (- (count token) 2))

    (and (str/ends-with? token "s")
         (> (count token) 3))
    (subs token 0 (dec (count token)))

    :else token))

(defn expanded-tokens
  [s]
  (let [base (tokens s)
        singulars (map singularize-token base)]
    (set (concat base singulars))))

(defn token-prefix-overlap
  [query-tokens candidate-tokens]
  (count
   (for [q query-tokens
         c candidate-tokens
         :when (or (str/starts-with? c q)
                   (str/starts-with? q c))]
     [q c])))

(defn score-text
  [query title description identifier]
  (let [query* (normalize query)
        title* (normalize title)
        description* (normalize description)
        identifier* (normalize identifier)
        title-tokens (expanded-tokens title*)
        description-tokens (expanded-tokens description*)
        query-tokens (expanded-tokens query*)
        title-overlap (count (set/intersection query-tokens title-tokens))
        description-overlap (count (set/intersection query-tokens description-tokens))
        prefix-overlap (token-prefix-overlap query-tokens title-tokens)
        title-jaro (* 120 (jaro-winkler query* title*))
        title-dice (* 90 (dice query* title*))
        description-jaro (* 25 (jaro-winkler query* description*))
        identifier-match (if (or (= identifier* query*)
                                 (str/includes? identifier* query*))
                           100
                           0)
        phrase-score (cond
                       (str/includes? title* query*) 120
                       (str/includes? description* query*) 40
                       :else 0)
        word-order-score (if (every? #(str/includes? title* %)
                                     (remove str/blank? (str/split query* #"\s+")))
                           50
                           0)
        prefix-score (* 12 prefix-overlap)
        token-score (+ (* 30 title-overlap)
                       (* 8 description-overlap))
        length-penalty (Math/abs ^long (- (count title*) (count query*)))]
    (- (+ identifier-match
          phrase-score
          word-order-score
          prefix-score
          token-score
          title-jaro
          title-dice
          description-jaro)
       (min 25 (quot length-penalty 10)))))

(defn rank-issues
  [query issues]
  (->> issues
       (map (fn [issue]
              (assoc issue
                     :score
                     (score-text query
                                 (:title issue)
                                 (:description issue)
                                 (domain/display-id issue)))))
       (sort-by (juxt (comp - :score) :title))))
