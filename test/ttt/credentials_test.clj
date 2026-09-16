(ns ttt.credentials-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [ttt.credentials :as credentials]))

(deftest helper-name-is-bounded
  (is (= "osxkeychain"
         (credentials/helper-name {:credential-helper "osxkeychain"} {})))
  (is (= "secretservice"
         (credentials/helper-name {:credential-helper "osxkeychain"}
                                  {"TTT_CREDENTIAL_HELPER" "secretservice"})))
  (is (thrown-with-msg?
       Exception
       #"Credential helper names"
       (credentials/helper-name {:credential-helper "../../command"} {}))))

(deftest credential-ids-are-profile-and-setting-specific
  (is (= "https://tickettrain.invalid/client/tracker/linear/api-key"
         (credentials/credential-id :client :tracker :linear :api-key)))
  (is (not= (credentials/credential-id :client :tracker :linear :api-key)
            (credentials/credential-id :work :tracker :linear :api-key))))

(deftest docker-credential-helper-protocol-keeps-secrets-on-stdin
  (let [calls (atom [])]
    (with-redefs [credentials/run-helper
                  (fn [_ action input]
                    (swap! calls conj [action input])
                    (if (= action "get")
                      (json/generate-string {:Username "tickettrain"
                                             :Secret "from-keychain"})
                      ""))]
      (is (= "from-keychain" (credentials/get-secret "osxkeychain" "credential-id")))
      (credentials/store-secret! "osxkeychain" "credential-id" "secret-value")
      (credentials/erase-secret! "osxkeychain" "credential-id"))
    (is (= ["get" "credential-id\n"] (first @calls)))
    (let [[action payload] (second @calls)]
      (is (= "store" action))
      (is (= {:ServerURL "credential-id"
              :Username "tickettrain"
              :Secret "secret-value"}
             (json/parse-string payload true))))
    (is (= ["erase" "credential-id\n"] (last @calls)))))
