(ns ttt.providers.tracker.pagination)

(defn collect-matches
  [fetch-page matches? limit]
  (loop [cursor nil
         matched []]
    (let [{:keys [items next-cursor]} (fetch-page cursor)
          next-matched (into matched (filter matches?) items)]
      (cond
        (>= (count next-matched) limit) (vec (take limit next-matched))
        (and (some? next-cursor) (not= cursor next-cursor)) (recur next-cursor next-matched)
        :else (vec next-matched)))))
