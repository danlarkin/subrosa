(ns subrosa.test.connection
  (:use [clojure.test]
        [subrosa.test.expect]))

(use-fixtures :each run-test-server)

(deftest basic-connect
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"Welcome to the .* dan$"))))

(deftest opposite-order-connect
  (with-connection s
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "NICK dan")
    (is (received? s #"Welcome to the .* dan$"))))

(deftest multiple-connections
  (with-connection s1
    (with-connection s2
      (transmit s1 "NICK dan1")
      (transmit s1 "USER dan 0 * :Dan Larkin")
      (is (received? s1 #"Welcome to the .* dan1$"))
      (transmit s2 "NICK dan2")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (is (received? s2 #"Welcome to the .* dan2$")))))

(deftest multiple-connections-same-nick
  (with-connection s1
    (with-connection s2
      (transmit s1 "NICK dan")
      (transmit s1 "USER dan 0 * :Dan Larkin")
      (is (received? s1 #"Welcome to the .* dan$"))
      (transmit s2 "NICK dan")
      (transmit s2 "USER dan 0 * :Dan Larkin")
      (is (received? s2 #"Nickname is already in use"))
      (transmit s2 "NICK dan2")
      (is (received? s2 #"Welcome to the .* dan2$")))))

(deftest invalid-nick
  (with-connection s
    (doseq [nick ["dan@" "dan!" "dan$"]]
      (transmit s (format "NICK %s" nick))
      (transmit s "USER dan 0 * :Dan Larkin")
      (is (received? s #"Erroneous nickname")))
    (transmit s "NICK")
    (is (received? s #"No nickname given"))
    (transmit s "NICK dan")
    (is (received? s #"Welcome to the .* dan$"))))

(deftest changing-nick
  (with-connection s
    (transmit s "NICK @dan")
    (transmit s "USER danlarkin 0 * :Dan Larkin")
    (is (received? s #":Erroneous nickname"))
    (transmit s "NICK foo")
    (is (received? s #"Welcome to the .* foo"))
    (transmit s "NICK optimus′")
    (is (received? s #":foo!danlarkin@.* NICK :optimus′"))))

(deftest incomplete-registration
  (with-connection s
    (transmit s "NICK dan")) ; incomplete registration
  ;; server should shut this connection
  ;; since the socket was closed client-side
  ;; TODO: add a check for that here
  )

(deftest send-user-after-authenticated
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"Welcome to the .* dan$"))
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"Unauthorized command"))))

(deftest user-with-wrong-args
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER")
    (is (received? s #"Not enough parameters"))
    (transmit s "USER dan 0 :whoopsie!")
    (is (received? s #"Not enough parameters"))
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"Welcome to the .* dan$"))))

(deftest ping-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "PING localhost")
    (is (received? s #"PONG .* :localhost"))
    (transmit s "PING")
    (is (received? s #"No origin specified"))))

(deftest case-insensitive-commands
  (with-connection s
    (transmit s "nick dan")
    (transmit s "user dan 0 * :Dan Larkin")
    (is (received? s #"Welcome to the .* dan$"))))

(deftest whois-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dddan 0 * :Dan Larkin")
    (transmit s "JOIN #foo")
    (transmit s "WHOIS superdan")
    (is (received? s #"No such nick/channel"))
    (transmit s "WHOIS dan")
    (is (received? s #"311 dan dddan .* \* :Dan Larkin"))
    (is (received? s #"319 dan dddan :#foo"))
    (is (received? s #":End of WHOIS list"))))

(deftest whois-no-nick-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "WHOIS")
    (is (received? s #":No nickname given"))))

(deftest nonextant-command
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (transmit s "THISCOMMANDDOESNOTEXIST")
    (is (received? s #":Unknown command"))))
