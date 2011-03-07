(ns subrosa.database
  (:refer-clojure :exclude [get])
  (:import [java.util UUID]))

(defonce db (atom {}))

(defn ensure-id [m]
  (if (:id m)
    m
    (assoc m :id (str (UUID/randomUUID)))))

(defn get
  ([db table]
     (vals (get-in db [table :id])))
  ([db table column value]
     (get-in db [table column value])))

(defn put [db table m]
  (let [m (ensure-id m)]
    (reduce (fn [a [k v]]
              (update-in a [table k] assoc v m))
            db
            m)))

(defn delete [db table id]
  (let [m (get db table :id id)]
    (assoc db
      table (reduce (fn [a [k v]]
                      (update-in a [k] dissoc v))
                    (db table)
                    m))))
