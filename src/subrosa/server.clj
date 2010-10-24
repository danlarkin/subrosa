(ns subrosa.server
  (:use [clojure.contrib.datalog.database :only [make-database]])
  (:import [java.util Date]
           [java.net InetAddress]))

(defn make-subrosa-db []
  (make-database
   (relation :user [:nick :real-name :user-name :channel :pending?])
   (index :user :nick)
   (index :user :channel)

   (relation :room [:name :topic])
   (index :room :name)

   (relation :user-in-room [:user-nick :room-name])
   (index :user-in-room :user-nick)
   (index :user-in-room :room-name)))

(defonce db (ref (make-subrosa-db)))

(defonce server {:host (.getHostName (InetAddress/getLocalHost))
                 :version "subrosa-1.0.0-SNAPSHOT"
                 :started (Date.)})

(defn reset-all-state! []
  (dosync
   (alter-var-root #'server assoc :started (Date.))
   (ref-set db (make-subrosa-db))))
