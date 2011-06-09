(ns subrosa.test.plugins.fs-logging
  (:use [clojure.test]
        [clojure.contrib.io :only [delete-file-recursively]]
        [subrosa.hooks :only [hooks]]
        [subrosa.plugins.fs-logging :only [get-log-name format-date
                                           format-time add-hooks]]
        [subrosa.config :only [config]]
        [subrosa.utils :only [with-var-root]]
        [subrosa.test.expect]))

(use-fixtures :once (fn [f]
                      (with-var-root [hooks (ref @hooks)
                                      format-date (constantly "2010-11-23")
                                      format-time (constantly "19:16:45")]
                        (add-hooks)
                        (delete-file-recursively
                         (config :plugins :fs-logging :directory) :silently)
                        (f)
                        (delete-file-recursively
                         (config :plugins :fs-logging :directory)))))

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
      (transmit s2 "NOTICE #foo :Hey, this is a notice")
      (Thread/sleep 500)
      (transmit s2 "PRIVMSG #foo :\u0001ACTION sends an action\u0001")
      (Thread/sleep 500)
      (transmit s1 "NICK superdan")
      (Thread/sleep 500)
      (transmit s1 "PRIVMSG #foo :new nick!")
      (Thread/sleep 500)
      (transmit s1 "TOPIC #foo :new topic!")
      (Thread/sleep 500)
      (transmit s1 "PART #foo")
      (Thread/sleep 500)
      (transmit s2 "QUIT :I'm outta here")))
  (Thread/sleep 1000)
  (let [lines (.split (slurp (get-log-name "#foo")) "\n")]
    (is (= 11 (count lines)))
    (is (=  (vec lines)
            ["19:16:45 --- join: dan (dan!dan@localhost) joined #foo"
             "19:16:45 --- join: dan2 (dan2!dan@localhost) joined #foo"
             "19:16:45 <dan> Hello, World!"
             "19:16:45 <dan2> Oh sweet. I see you, dan!"
             "19:16:45 -dan2- Hey, this is a notice"
             "19:16:45 *dan2 sends an action"
             "19:16:45 --- nick: dan is now known as superdan"
             "19:16:45 <superdan> new nick!"
             "19:16:45 --- topic: superdan set the topic to \"new topic!\""
             "19:16:45 --- part: superdan (Part: superdan)"
             "19:16:45 --- quit: dan2 (Quit: I'm outta here)"]))))
