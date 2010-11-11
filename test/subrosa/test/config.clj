(ns subrosa.test.config
  (:use [clojure.test]
        [subrosa.config]))

(deftest test-config
  (is (true? (map? (config)))))
