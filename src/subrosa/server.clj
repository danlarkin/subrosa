(ns subrosa.server
  (:import [java.util Date]
           [java.net InetAddress]))

(defonce +server+ {:host (.getHostName (InetAddress/getLocalHost))
                   :version "subrosa-1.0.0-SNAPSHOT"
                   :started (Date.)})
(defonce +pending-connections+ (ref {}))  ; channel -> successful commands set
(defonce +channels+ (ref {}))             ; channel -> nick
(defonce +nicks+ (ref {}))                ; nick    -> user info map
;;                                                     {:user-name ""
;;                                                      :real-name ""}
(defonce +rooms+ (ref {}))                ; room    -> room info map
;;                                                     {:nicks #{}
;;                                                      :topic ""
;;                                                     }

(defn reset-all-state! []
  (dosync
   (alter-var-root #'+server+ assoc :started (Date.))
   (ref-set +pending-connections+ {})
   (ref-set +channels+ {})
   (ref-set +nicks+ {})
   (ref-set +rooms+ {})))
