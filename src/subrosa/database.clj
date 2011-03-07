(ns subrosa.database
  (:refer-clojure :exclude [get])
  (:import [java.util UUID]))

(defonce db (ref {}))

(defn ensure-id [m]
  (if (:id m)
    m
    (assoc m :id (str (UUID/randomUUID)))))

(defn get
  ([table]
     (vals (get-in @db [table :id])))
  ([table column value]
     (get-in @db [table column value])))

(defn delete [table id]
  (dosync
   (let [m (get table :id id)]
     (alter db assoc table (reduce (fn [a [k v]]
                                     (update-in a [k] dissoc v))
                                   (@db table)
                                   m)))))

(defn put [table m]
  (dosync
   (let [m (ensure-id m)]
     (delete table (:id m))
     (alter db assoc table (reduce (fn [a [k v]]
                                     (update-in a [k] assoc v m))
                                   (@db table)
                                   m)))))
