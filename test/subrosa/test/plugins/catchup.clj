(ns subrosa.test.plugins.catchup
  (:use [clojure.test]
        [subrosa.test.expect]))

(use-fixtures :each run-test-server)

(deftest test-catchup
  (with-connection s1
    (with-connection s2
      (transmit s1 "NICK lee")
      (transmit s1 "USER lee 0 * :Lee Hinmanm")
      (transmit s1 "JOIN #foo")
      (Thread/sleep 500)
      (transmit s1 "PRIVMSG #foo :Hello, World!")
      (Thread/sleep 500)
      (transmit s2 "NICK lee2")
      (transmit s2 "USER lee2 0 * :Lee Hinmanm")
      (transmit s2 "JOIN #foo")
      (Thread/sleep 500)
      (transmit s2 "PRIVMSG #foo :Goodbye, World!")
      (Thread/sleep 500)
      (transmit s1 "CATCHUP #foo 1")
      (Thread/sleep 500)
      (is (received? s1 #"Goodbye, World!"))
      (transmit s1 "CATCHUP #foo")
      (Thread/sleep 500)
      (is (received? s1 #"Hello, World!"))
      (transmit s1 "PART #foo")
      (Thread/sleep 500)
      (transmit s1 "QUIT"))))

(deftest test-catchup-errors
  (with-connection s1
    (transmit s1 "NICK lee")
    (transmit s1 "USER lee 0 * :Lee Hinmanm")
    (transmit s1 "JOIN #foo")
    (Thread/sleep 500)
    (transmit s1 "PRIVMSG #foo :Hello, World!")
    (Thread/sleep 500)
    (transmit s1 "CATCHUP #bar")
    (is (received? s1 #"You are not in that room"))
    (Thread/sleep 500)
    (transmit s1 "CATCHUP #foo not-a-number")
    (is (received? s1 #"Hello, World!"))
    (Thread/sleep 500)
    (transmit s1 "PART #foo")
    (Thread/sleep 500)
    (transmit s1 "QUIT")))
