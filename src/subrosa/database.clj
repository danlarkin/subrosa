(ns subrosa.database
  (:refer-clojure :exclude [get])
  (:import [java.util UUID]))

(defonce db (ref {}))

(defn ensure-id [m]
  (if (:id m)
    m
    (assoc m :id (str (UUID/randomUUID)))))

(defn get*
  ([db table]
     (vals (get-in db [table :id])))
  ([db table column value]
     (get-in db [table column value])))

(defn get
  ([table]
     (get* @db table))
  ([table column value]
     (get* @db table column value)))

(defn delete* [db table m]
  (assoc db table (reduce (fn [a [k v]]
                            (update-in a [k] dissoc v))
                          (db table)
                          m)))

(defn delete [table id]
  (dosync
   (let [m (get table :id id)]
     (alter db delete* table m))))

(defn put* [db table m]
  (assoc db table (reduce (fn [a [k v]]
                            (update-in a [k] assoc v m))
                          (db table)
                          m)))

(defn put [table m]
  (dosync
   (let [m (ensure-id m)]
     (alter db (fn [db]
                 (-> db
                     (delete* table m)
                     (put* table m)))))))
