(ns subrosa.server
  (:use [clojure.contrib.datalog.database :only [make-database]])
  (:import [java.util Date]
           [java.net InetAddress]))

(defn make-subrosa-db []
  (make-database
   (relation :user [:nick :real-name :user-name :channel :pending? :login-time])
   (index :user :nick)
   (index :user :channel)

   (relation :room [:name :topic])
   (index :room :name)

   (relation :user-in-room [:user-nick :room-name])
   (index :user-in-room :user-nick)
   (index :user-in-room :room-name)

   (relation :message [:time :text :room :nick])
   (index :time :text)))

(defonce db (ref (make-subrosa-db)))

(defonce server {:host (.getHostName (InetAddress/getLocalHost))
                 :version (str "subrosa-" (.trim (slurp "etc/version.txt")))
                 :started (Date.)})

(defn reset-all-state! []
  (dosync
   (alter-var-root #'server assoc :started (Date.))
   (ref-set db (make-subrosa-db))))
