(ns subrosa.test.chat
  (:require [clojure.test :refer :all]
            [subrosa.test.expect :refer :all]))

(use-fixtures :each run-test-server)

(deftest basic-privmsg-to-room
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (is (received? s1 #"JOIN #foo"))
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "PRIVMSG #foo :Hello, World!")
      (Thread/sleep 1000)) ; give some time for the message to get to the server
    (is (received? s1 #"dan2!dan@.* PRIVMSG #foo :Hello, World!"))))

(deftest basic-privmsg-to-room-missing-colon
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (is (received? s1 #"JOIN #foo"))
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "PRIVMSG #foo hello")
      (Thread/sleep 1000)) ; give some time for the message to get to the server
    (is (received? s1 #"dan2!dan@.* PRIVMSG #foo :hello"))))

(deftest basic-privmsg-to-user
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (is (received? s1 #"Welcome to the .* dan$"))
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "PRIVMSG dan :Hello, Dan!")
      (Thread/sleep 1000)) ; give some time for the message to get to the server
    (is (received? s1 #"dan2!dan@.* PRIVMSG dan :Hello, Dan!"))))

(deftest privmsg-error-cases
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "PRIVMSG")
    (is (received? s #"No recipient given"))
    (transmit s "PRIVMSG dan")
    (is (received? s #"No text to send"))))

(deftest notice-to-room
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (is (received? s1 #"JOIN #foo"))
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "NOTICE #foo :Hello, World!")
      (Thread/sleep 1000)) ; give some time for the message to get to the server
    (is (received? s1 #"dan2!dan@.* NOTICE #foo :Hello, World!"))))

(deftest notice-to-room-missing-colon
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (is (received? s1 #"JOIN #foo"))
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "NOTICE #foo hello")
      (Thread/sleep 1000)) ; give some time for the message to get to the server
    (is (received? s1 #"dan2!dan@.* NOTICE #foo :hello"))))


(deftest notice-to-user
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "NOTICE dan :Hello, World!")
      (Thread/sleep 1000)) ; give some time for the message to get to the server
    (is (received? s1 #"dan2!dan@.* NOTICE dan :Hello, World!"))))

(deftest notice-never-errors
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "NOTICE")
    (is (not-received? s #"411"))
    (transmit s "NOTICE dan")
    (is (not-received? s #"412"))
    (transmit s "NOTICE foobar :hey")
    (is (not-received? s #"401"))))
