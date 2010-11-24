(ns subrosa.test.plugins.logging
  (:use [clojure.test]
        [clojure.contrib.io :only [delete-file-recursively]]
        [subrosa.plugins.logging :only [get-log-name format-date format-time]]
        [subrosa.config :only [config config-override]]
        [subrosa.utils :only [with-var-root]]
        [subrosa.test.expect]))

(use-fixtures :once (fn [f]
                      (with-var-root [config (config-override
                                              {:logging {:directory "log"}})
                                      format-date (constantly "2010-11-23")
                                      format-time (constantly "19:16:45")]
                        (delete-file-recursively "log" :silently)
                        (f)
                        (delete-file-recursively "log"))))

(use-fixtures :each run-test-server)

(deftest test-logging
  (with-connection s1
    (with-connection s2
      (transmit s1 "NICK dan")
      (transmit s1 "USER dan 0 * :Dan Larkin")
      (transmit s1 "JOIN #foo")
      (Thread/sleep 500)
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (Thread/sleep 500)
      (transmit s1 "PRIVMSG #foo :Hello, World!")
      (Thread/sleep 500)
      (transmit s2 "PRIVMSG #foo :Oh sweet. I see you, dan!")
      (Thread/sleep 500)
      (transmit s1 "PART #foo")
      (Thread/sleep 500)
      (transmit s2 "QUIT")))
  (Thread/sleep 1000)
  (let [[l1 l2 l3 l4 l5 l6 :as lines] (.split
                                       (slurp (get-log-name "#foo")) "\n")]
    (is (= 6 (count lines)))
    (is (= l1
           "19:16:45 --- join: dan (dan!dan@localhost) joined #foo"))
    (is (= l2
           "19:16:45 --- join: dan2 (dan2!dan@localhost) joined #foo"))
    (is (= l3 "19:16:45 <dan> Hello, World!"))
    (is (= l4 "19:16:45 <dan2> Oh sweet. I see you, dan!"))
    (is (= l5 "19:16:45 --- part: dan left #foo"))
    (is (= l6 "19:16:45 --- quit: dan2 (Quit: Client Quit)"))))
