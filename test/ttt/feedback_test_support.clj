(ns ttt.feedback-test-support
  (:require [cheshire.core :as json]
            [clojure.test :refer [is]]
            [ttt.cli.agent :as agent]
            [ttt.platform.feedback-journal :as journal]))

(defn with-journal [run]
  (let [root (.toFile (java.nio.file.Files/createTempDirectory
                      "ttt-forge-feedback-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (with-redefs [journal/directory (constantly root)] (run))
         (finally (doseq [file (reverse (file-seq root))] (.delete file))))))

(defn request [operations]
  {:action "review_change_request" :changeRequest "7" :batchId "batch-one"
   :expectedHead "head-a" :operations operations})

(defn preview [request]
  (agent/run ["preview" "--request" (json/generate-string request)]))

(defn apply-request [request proposal]
  (agent/run ["apply" "--request" (json/generate-string request) "--approve" proposal]))

(defn proposal-id [request]
  (let [result (preview request)]
    (is (= 0 (:exit result)) (pr-str result))
    (get-in result [:envelope :data :proposalId])))
