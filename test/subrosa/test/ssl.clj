(ns subrosa.test.ssl
  (:use [clojure.test]
        [subrosa.test.expect]
        [subrosa.config :only [config config-override]]
        [subrosa.utils :only [with-var-root]]))

(use-fixtures :once (fn [f]
                      (with-var-root [config (config-override
                                              {:ssl {:keystore "test.ks"
                                                     :password "foobar"}})]
                        (f))))

(use-fixtures :each run-test-server)

(deftest basic-connect-with-ssl
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"Welcome to the .* dan$"))))
