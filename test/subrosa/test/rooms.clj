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

(deftest part
  (testing "a user"
    (with-connection user
      (transmit user "NICK don")
      (transmit user "USER don 0 * :Don K. Bahls")
      (transmit user "JOIN #foo")
      (with-connection observer
        (transmit observer "NICK x")
        (transmit observer "USER x 0 * :USER X")
        (transmit observer "JOIN #foo")
        (transmit observer "JOIN #lolcats")
        (testing "requesting to part"
          (testing "should receive get an error"
            (testing "if they don't provide a channel to part"
              (transmit user "PART")
              (is (received? user #"461 don :Not enough parameters")))
            (testing "if the channel doesn't exist"
              (transmit user "PART #bahls")
              (is (received? user #"403 don :No such channel")))
            (testing "if they aren't in the channel"
              (transmit user "PART #lolcats")
              (is (received? user #"442 don :You're not on that channel"))))
          (transmit user "PART #foo")
          (testing "should trigger a part message sent to the whole channel"
            (doseq [x [user observer]]
              (is (received? x #":don!don@localhost PART #foo :don")))))))))

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

(deftest list-rooms
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "LIST")
    (is (received? s #"323 dan :End of LIST"))
    (transmit s "JOIN #foo")
    (transmit s "JOIN #foo2")
    (transmit s "LIST")
    (is (received? s #"322 dan #foo2 0 :$"))
    (is (received? s #"322 dan #foo 0 :$"))
    (is (received? s #"323 dan :End of LIST"))
    (transmit s "LIST #foo")
    (is (received? s #"322 dan #foo 0 :$"))
    (is (received? s #"323 dan :End of LIST"))))
