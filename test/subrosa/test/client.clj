(ns subrosa.test.client
  (:require [clojure.test :refer :all]
            [subrosa.test.expect :refer :all]))

(use-fixtures :each run-test-server remove-motd)

(deftest without-motd-file
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #":MOTD File is missing"))))

(deftest with-motd-file
  (spit "etc/motd" "expected MOTD content")
  (with-connection s
    (transmit s "NICK dan")
    (transmit s "USER dan 0 * :Dan Larkin")
    (is (received? s #"expected MOTD content"))))

