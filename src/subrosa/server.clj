(ns subrosa.server
  (:require [subrosa.database :as db])
  (:use [subrosa.config :only [config]])
  (:import [java.util Date]
           [java.net InetAddress]))

;; database schema:
;; :user => [:nick :real-name :user-name :quit-message :channel :pending?]
;; :room => [:name :topic]
;; :user-in-room => [:user-nick :room-name]

(defonce server {:host (config :host)
                 :version (try
                            (str "subrosa-" (.trim (slurp "etc/version.txt")))
                            (catch Exception e
                              "subrosa-UNKNOWN"))
                 :started (Date.)})

(defn reset-all-state! []
  (alter-var-root #'server assoc :started (Date.))
  (dosync (ref-set db/db {}))
  (db/add-index :user :nick)
  (db/add-index :user :channel)
  (db/add-index :room :name)
  (db/add-index :user-in-room :user-nick :list true)
  (db/add-index :user-in-room :room-name :list true)
  (db/add-index :user-in-room [:user-nick :room-name]))
