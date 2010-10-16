(ns subrosa.test.rooms
  (:use [clojure.test]
        [clojure.string :only [join]]
        [subrosa.test.expect]))

(use-fixtures :each run-test-server)

(deftest basic-join
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "JOIN")
    (is (received? s #"Not enough parameters"))
    (transmit s "JOIN foo bar baz")
    (is (received? s #"Too many parameters"))
    (transmit s "JOIN #foo")
    (is (received? s #"JOIN #foo"))
    (is (received? s #"No topic is set"))
    (is (received? s #"353 dan = #foo :dan"))
    (is (received? s #"End of NAMES list"))))

(deftest topic-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "TOPIC")
    (is (received? s #":Not enough parameters"))
    (transmit s "TOPIC #notachannel")
    (is (received? s #":No such channel"))
    (transmit s "JOIN #foo")
    (transmit s "TOPIC #foo")
    (is (received? s #"331 dan #foo :No topic is set"))))

(deftest names-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "NAMES")
    (is (received? s #"End of NAMES list"))
    (transmit s "NAMES #foo")
    (is (received? s #"End of NAMES list"))
    (transmit s "JOIN #foo")
    (transmit s "NAMES #foo")
    (is (received? s #"353 dan = #foo :dan"))
    (is (received? s #"End of NAMES list"))))

(deftest all-clients-receive-join
  (with-connection s1
    (transmit s1 "NICK dan1")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (is (received? s1 #":dan2!dan@localhost JOIN #foo")))))
