(ns subrosa.plugins.catchup
  (:use [subrosa.commands :only [defcommand]]
        [subrosa.config :only [config]]
        [subrosa.client]
        [subrosa.hooks :only [add-hook]]
        [clojure.contrib.condition :only [raise]])
  (:import (java.util Date)
           (java.text SimpleDateFormat)))

(defn- format-time []
  (.format (SimpleDateFormat. (config :catchup :time-format)) (Date.)))

(def msg-db-agent (agent {}))

(defn- add-msg-fn [msg]
  (fn update-msg-agent [db]
    (let [room (:room msg)
          text (:msg msg)]
      (if (db room)
        ;; have the room already
        (do
          (swap! (db room) (fn update-msg-atom [q]
                             (conj (if (>= (count q)
                                           (config :catchup :max-msgs-per-room))
                                     (pop q)
                                     q)
                                   text)))
          db)
        ;; don't have the room
        (assoc db room (atom (-> (clojure.lang.PersistentQueue/EMPTY)
                                 (conj text))))))))

(defn add-msg [room-name msg]
  (send msg-db-agent
        (add-msg-fn {:room room-name :msg msg})))

(defn messages-for [room]
  (if (@msg-db-agent room)
    (seq @(@msg-db-agent room))
    []))

(defmulti catchup-dispatch (fn [& args] (first args)))

(defmethod catchup-dispatch 'privmsg-room-hook [hook channel room-name msg]
  (add-msg room-name (format "<%s> [%s] %s"
                             (nick-for-channel channel)
                             (format-time)
                             msg)))

(defmethod catchup-dispatch 'join-hook [hook channel room-name]
  (add-msg room-name
           (format "--- join: %s (%s) joined %s"
                   (nick-for-channel channel)
                   (format-client channel)
                   room-name)))

(defmethod catchup-dispatch 'part-hook [hook channel room-name]
  (add-msg room-name
           (format "--- part: %s left %s"
                   (nick-for-channel channel)
                   room-name)))

(defmethod catchup-dispatch 'quit-hook [hook channel quit-msg]
  (doseq [room-name (rooms-for-nick (nick-for-channel channel))]
    (add-msg room-name
             (format "--- quit: %s (Quit: %s)"
                     (nick-for-channel channel)
                     (or quit-msg "Client Quit")
                     room-name))))

(defmethod catchup-dispatch 'nick-hook [hook channel old-nick new-nick]
  (doseq [room-name (rooms-for-nick old-nick)]
    (add-msg room-name
             (format "--- nick: %s is now known as %s" old-nick new-nick))))

(defmethod catchup-dispatch 'topic-hook [hook channel room-name old-topic
                                         new-topic]
  (add-msg room-name
           (format "--- topic: %s set the topic to \"%s\""
                   (nick-for-channel channel) new-topic)))

(defn catchup-log [& args]
  (apply catchup-dispatch args))

(add-hook ::catchup 'privmsg-room-hook catchup-log)
(add-hook ::catchup 'join-hook catchup-log)
(add-hook ::catchup 'part-hook catchup-log)
(add-hook ::catchup 'quit-hook catchup-log)
(add-hook ::catchup 'nick-hook catchup-log)
(add-hook ::catchup 'topic-hook catchup-log)

(defcommand catchup [channel args]
  (let [[room size] (.split args " ")
        size (try (Integer/parseInt size)
                  (catch Exception _
                    (config :catchup :default-catchup-size)))
        begin (format ":%s PRIVMSG %s :%s" (format-client channel)
                      room (str "*** Catchup Playback for " room ":"))
        end (format ":%s PRIVMSG %s :%s" (format-client channel)
                    room (str "*** Catchup Complete."))]
    (if (room-for-name room)
      (let [all-messages (messages-for room)
            msgs (drop (- (count all-messages)
                          (or size (config :catchup :default-catchup-size)))
                       all-messages)]
        (send-to-client* channel begin)
        (doseq [m msgs]
          (send-to-client* channel
                           (format ":%s PRIVMSG %s :%s"
                                   (format-client channel) room m)))
        (send-to-client* channel end))
      (raise {:type :client-error
              :code 999
              :msg ":No room given or room does not exist"}))))
