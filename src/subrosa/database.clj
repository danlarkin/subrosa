(ns subrosa.database
  (:refer-clojure :exclude [get])
  (:import [java.util UUID]))

(defonce db (ref {}))

(defn ensure-id [m]
  (if (:id m)
    m
    (assoc m :id (str (UUID/randomUUID)))))

(defn add-index* [db table field-or-fields]
  (update-in db [table :indices] (comp set conj) field-or-fields))

(defn add-index [table field-or-fields]
  (dosync
   (alter db add-index* table field-or-fields)))

(defn get-indices* [db table]
  (conj (get-in db [table :indices]) :id))

(defn get-indices [table]
  (dosync
   (get-indices* @db table)))

(defn get*
  ([db table]
     (set (vals (get-in db [table :data :id]))))
  ([db table column value]
     (get-in db [table :data column value])))

(defn get
  ([table]
     (get* @db table))
  ([table column value]
     (get* @db table column value)))

(defn select-from-indices [db table m]
  (into {} (for [index (get-indices* db table)]
             [index (if (coll? index)
                      (map m index)
                      (m index))])))

(defn delete* [db table id]
  (update-in db [table] assoc
             :data (reduce (fn [a [k v]]
                             (update-in a [k] dissoc v))
                           (get-in db [table :data])
                           (select-from-indices
                            db table (or (get* db table :id id)
                                         {})))))

(defn delete [table id]
  (dosync
   (alter db delete* table id)))

(defn put* [db table m]
  (update-in db [table] assoc
             :data (reduce (fn [a [k v]]
                             (update-in a [k] assoc v m))
                           (get-in db [table :data])
                           (select-from-indices db table m))))

(defn put [table m]
  (dosync
   (let [m (ensure-id m)]
     (alter db (fn [db]
                 (-> db
                     (delete* table (:id m))
                     (put* table m)))))))
