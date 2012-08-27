(ns subrosa.database
  (:refer-clojure :exclude [get])
  (:import [java.util UUID]))

(defonce ^:dynamic db (ref {}))

(defn ensure-id [m]
  (if (:id m)
    m
    (assoc m :id (str (UUID/randomUUID)))))

(defn add-index* [db table field-or-fields opts]
  (update-in db [table :indices] assoc field-or-fields opts))

(defn add-index [table field-or-fields & {:as opts}]
  (dosync
   (alter db add-index* table field-or-fields opts)))

(defn get-indices* [db table]
  (assoc (get-in db [table :indices]) :id {}))

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
  (into {} (for [[index opts] (get-indices* db table)]
             [index {:opts opts
                     :data (if (coll? index)
                             (map m index)
                             (m index))}])))

(defn dissoc-if-empty [m ks]
  (if (empty? (get-in m ks))
    (update-in m (butlast ks) dissoc (last ks))
    m))

(defn delete* [db table id]
  (let [m (get* db table :id id)]
    (update-in db [table] assoc
               :data (reduce (fn [a [index {:keys [opts data]}]]
                               (if (:list opts)
                                 (-> a
                                     (update-in [index data] disj m)
                                     (dissoc-if-empty [index data]))
                                 (update-in a [index] dissoc data)))
                             (get-in db [table :data])
                             (select-from-indices db table (or m {}))))))

(defn delete [table id]
  (dosync
   (alter db delete* table id)))

(defn put* [db table m]
  (update-in db [table] assoc
             :data (reduce (fn [a [index {:keys [opts data]}]]
                             (if (:list opts)
                               (update-in a [index data] (comp set conj) m)
                               (update-in a [index] assoc data m)))
                           (get-in db [table :data])
                           (select-from-indices db table m))))

(defn put [table m]
  (dosync
   (let [m (ensure-id m)]
     (alter db (fn [db]
                 (-> db
                     (delete* table (:id m))
                     (put* table m)))))))
