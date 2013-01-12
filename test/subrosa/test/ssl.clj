(ns subrosa.test.ssl
  (:require [carica.core :refer [config override-config]]
            [clojure.test :refer :all]
            [subrosa.test.expect :refer :all]
            [subrosa.utils :refer [with-var-root]]))

(use-fixtures :once (fn [f]
                      (with-var-root [config (override-config
                                              {:ssl {:keystore "test.ks"
                                                     :password "foobar"}})]
                        (f))))

(use-fixtures :each run-test-server)

(deftest basic-connect-with-ssl
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"Welcome to the .* dan$"))))
