(ns subrosa.test.database
  (:require [subrosa.database :as db])
  (:use [clojure.test]))

(use-fixtures :each
              (fn [f]
                (dosync (ref-set db/db {}))
                (f)))

(defn =* [m1 m2]
  (= (dissoc m1 :id)
     (dissoc m2 :id)))

(deftest basic-database-operations
  (let [m {:foo 42 :nick "dan"}]
    (db/put :foo m)
    (is (=* (db/get :foo :foo 42)
            m))
    (is (=* (db/get :foo :nick "dan")
            m))
    (db/delete :foo (:id (db/get :foo :foo 42)))
    (is (nil? (db/get :foo :foo 42)))
    (is (nil? (db/get :foo :nick "dan")))))
