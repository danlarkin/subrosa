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

(deftest part-command
  (with-connection s1
    (transmit s1 "NICK dan")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "JOIN #foo2")
      (Thread/sleep 1000)
      (transmit s1 "PART")
      (is (received? s1 #"461 dan PART :Not enough parameters"))
      (transmit s1 "PART #foobar")
      (is (received? s1 #"403 dan #foobar :No such channel"))
      (transmit s1 "PART #foo2")
      (is (received? s1 #"442 dan #foo2 :You're not on that channel"))
      (transmit s1 "PART #foo")
      (is (received? s1 #":dan!dan@.* PART #foo :dan"))
      (is (received? s2 #":dan!dan@.* PART #foo :dan"))
      (transmit s2 "PART #foo :Hey guys I'm outta here!")
      (is (received? s2 #":dan2!dan@.* PART #foo :Hey guys I'm outta here!")))))

(deftest topic-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "TOPIC")
    (is (received? s #"TOPIC :Not enough parameters"))
    (transmit s "TOPIC #notachannel")
    (is (received? s #":No such channel"))
    (transmit s "JOIN #foo")
    (transmit s "TOPIC #foo")
    (is (received? s #"331 dan #foo :No topic is set"))
    (transmit s "TOPIC #foo :awesome topic")
    (is (received? s #":dan!dan@.* TOPIC #foo :awesome topic"))))

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
    (Thread/sleep 1000) ; Give dan1 a chance to authenticate
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (is (received? s1 #":dan2!dan@.* JOIN #foo")))))

(deftest all-clients-receive-quit
  (with-connection s1
    (transmit s1 "NICK dan1")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (Thread/sleep 1000) ; Give dan1 a chance to authenticate
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "QUIT")
      (is (received? s1 #":dan2!dan@.* QUIT :Client Quit")))))

(deftest all-clients-receive-nick-change
  (with-connection s1
    (transmit s1 "NICK dan1")
    (transmit s1 "USER dan 0 * :Dan Larkin")
    (transmit s1 "JOIN #foo")
    (Thread/sleep 1000) ; Give dan1 a chance to authenticate
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s2 "JOIN #foo")
      (transmit s2 "NICK superdan")
      (is (received? s1 #":dan2!dan@.* NICK :superdan")))))

(deftest list-rooms
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (with-connection s2
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (transmit s "LIST")
      (is (received? s #"323 dan :End of LIST"))
      (transmit s "JOIN #foo")
      (transmit s "JOIN #foo2")
      (transmit s2 "JOIN #foo")
      (transmit s "TOPIC #foo2 :new topic")
      (Thread/sleep 500) ; give time for all rooms to be created
      (transmit s "LIST")
      (is (received? s #"322 dan #foo2 1 :new topic$"))
      (is (received? s #"322 dan #foo 2 :$"))
      (is (received? s #"323 dan :End of LIST"))
      (transmit s2 "PART #foo")
      (Thread/sleep 500)
      (transmit s "LIST #foo")
      (is (received? s #"322 dan #foo 2 :$"))
      (is (received? s #"323 dan :End of LIST")))))

(deftest list-non-extant-room
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "LIST #foo")
    (is (not-received? s #"322"))))
