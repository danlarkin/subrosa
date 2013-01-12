(ns subrosa.test.database
  (:require [clojure.test :refer :all]
            [subrosa.database :as db]))

(use-fixtures :each
              (fn [f]
                (binding [db/db (ref {})]
                  (f))))

(deftest basic-database-operations
  (let [m {:baz 42 :nick "dan" :id "foo"}
        m2 {:baz "baz" :nick "dan2" :id "foo2"}]
    (testing "put one row and retrieve it"
      (db/put :table1 m)
      (is (= m (db/get :table1 :id "foo"))))
    (testing "put another row and retrieve it"
      (db/put :table1 m2)
      (is (= m2 (db/get :table1 :id "foo2"))))
    (testing "make sure the table has two rows"
      (is (= #{m m2} (db/get :table1))))
    (testing "delete one row and make sure it's gone"
      (db/delete :table1 "foo")
      (is (nil? (db/get :table :id "foo"))))))

(deftest put-should-update-not-replace
  (let [m {:baz 42 :nick "dan" :id "foo"}
        m2 (assoc m :nick "dan2")]
    (db/put :table1 m)
    (db/put :table1 m2)
    (is (= m2 (db/get :table1 :id "foo")))
    (is (= #{m2} (db/get :table1)))))

(deftest put-should-create-id-field-if-missing
  (let [m {:baz 42 :nick "dan"}]
    (db/put :table1 m)
    (let [new-m (first (db/get :table1))]
      (is (not (nil? (:id new-m)))))))

(deftest get-shouldnt-work-with-unindexed-field
  (let [m {:baz 42 :nick "dan" :id "foo"}]
    (db/put :table1 m)
    (is (nil? (db/get :table1 :nick "dan")))
    (is (= #{m} (db/get :table1)))))

(deftest test-add-index ; get-shouldnt-work-with-unindexed-field
  (let [m {:baz 42 :nick "dan" :id "foo"}]
    (db/add-index :table1 :nick)
    (db/put :table1 m)
    (is (= m (db/get :table1 :nick "dan")))
    (is (= #{m} (db/get :table1)))))

(deftest test-complex-index
  (let [m {:baz 42 :nick "dan" :id "foo"}]
    (db/add-index :table1 [:baz :nick])
    (db/put :table1 m)
    (is (= m (db/get :table1 [:baz :nick] [42 "dan"])))
    (is (= #{m} (db/get :table1)))))

(deftest complex-index-are-updated
  (let [m {:baz 42 :nick "dan" :id "foo"}
        m2 (assoc m :nick "dan2")]
    (db/add-index :table1 [:baz :nick])
    (db/put :table1 m)
    (is (= m (db/get :table1 [:baz :nick] [42 "dan"])))
    (db/put :table1 m2)
    (is (nil? (db/get :table1 [:baz :nick] [42 "dan"])))
    (is (= m2 (db/get :table1 [:baz :nick] [42 "dan2"])))
    (is (= #{m2} (db/get :table1)))))

(deftest test-list-index
  (let [m {:baz 42 :nick "dan" :id "foo"}
        m2 {:baz 3 :nick "dan" :id "foo2"}]
    (db/add-index :table1 :nick :list true)
    (db/put :table1 m)
    (db/put :table1 m2)
    (is (= #{m m2} (db/get :table1 :nick "dan")))
    (is (= #{m m2} (db/get :table1)))
    (db/delete :table1 "foo")
    (is (= #{m2} (db/get :table1 :nick "dan")))))
