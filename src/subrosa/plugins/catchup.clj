(ns subrosa.plugins.catchup
  (:require [carica.core :refer [config]]
            [slingshot.slingshot :refer [throw+]]
            [subrosa.client :refer :all]
            [subrosa.commands :refer [defcommand]]
            [subrosa.hooks :refer [add-hook]])
  (:import (java.text SimpleDateFormat)
           (java.util Date)))

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
   (commute buffer update-in [room-name] maybe-conj
            [(nick-for-channel channel)
             (if (= hook 'privmsg-room-hook)
               "PRIVMSG"
               "NOTICE")
             (if-let [msg (second (re-find #"^\u0001ACTION (.*)\u0001$" msg))]
               (format "\u0001ACTION [%s] %s\u0001" (format-time) msg)
               (format "[%s] %s" (format-time) msg))])))

(defn add-hooks []
  (add-hook ::catchup 'privmsg-room-hook add-message)
  (add-hook ::catchup 'notice-room-hook add-message))

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
        (if (and (nick-in-room? nick room)
                 (not (room-is-private? room)))
          (do
            (send-to-client*
             channel (format ":*** PRIVMSG %s :%s" room "Catchup Playback"))
            (doseq [[sender type msg] (take-last size (@buffer room))]
              (send-to-client* channel
                               (format ":%s %s %s :%s"
                                       sender type room msg)))
            (send-to-client*
             channel (format ":*** PRIVMSG %s :%s" room "Catchup Complete")))
          (throw+ {:type :client-error
                   :code 442
                   :msg (format "%s :You're not on that channel" room)}))))))
