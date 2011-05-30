(ns subrosa.plugins.catchup
  (:use [subrosa.commands :only [defcommand]]
        [subrosa.config :only [config]]
        [subrosa.client]
        [subrosa.hooks :only [add-hook]]
        [clojure.contrib.condition :only [raise]])
  (:import (java.util Date)
           (java.text SimpleDateFormat)))

(defn format-time []
  (.format (SimpleDateFormat. "EEE - HH:mm:ss") (Date.)))

(defonce buffer (ref {}))

(defn maybe-conj [c v]
  (if c
    (conj (if (>= (count c) (config :plugins :catchup :max-msgs-per-room))
            (pop c)
            c)
          v)
    (conj (clojure.lang.PersistentQueue/EMPTY) v)))

(defn add-message [hook channel room-name msg]
  (dosync
   (commute buffer update-in [room-name] maybe-conj [(nick-for-channel channel)
                                                    (format "[%s] %s"
                                                            (format-time)
                                                            msg)])))

(defn add-hooks []
  (add-hook ::catchup 'privmsg-room-hook add-message))

(when (config :plugins :catchup :enabled?)
  (add-hooks)

  (defcommand catchup [channel args]
    (let [[room size] (.split args " ")
          default-size (config :plugins :catchup :default-playback-size)
          size (try
                 (Integer/parseInt size)
                 (catch Exception _
                   default-size))
          nick (nick-for-channel channel)
          rooms (if (empty? room)
                  (rooms-for-nick nick)
                  [room])]
      (doseq [room rooms]
        (if (nick-in-room? nick room)
          (do
            (send-to-client*
             channel (format ":*** PRIVMSG %s :%s" room "Catchup Playback"))
            (doseq [[sender msg] (take-last size (@buffer room))]
              (send-to-client* channel
                               (format ":%s PRIVMSG %s :%s"
                                       sender room msg)))
            (send-to-client*
             channel (format ":*** PRIVMSG %s :%s" room "Catchup Complete")))
          (raise {:type :client-error
                  :code 999
                  :msg ":You are not in that room"}))))))
