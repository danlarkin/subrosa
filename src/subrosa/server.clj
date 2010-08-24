(ns subrosa.server
  (:import [java.util Date]
           [java.net InetAddress]))

(defonce +server+ {:host (.getHostName (InetAddress/getLocalHost))
                   :version "subrosa-1.0.0-SNAPSHOT"
                   :started (Date.)})
(defonce +pending-connections+ (ref {}))  ; channel -> [ successful commands ]
(defonce +channels+ (ref {}))             ; channel -> nick
(defonce +nicks+ (ref {}))                ; nick    -> user info?

(defn reset-all-state! []
  (dosync
   (alter-var-root #'+server+ assoc :started (Date.))
   (ref-set +pending-connections+ {})
   (ref-set +channels+ {})
   (ref-set +nicks+ {})))
