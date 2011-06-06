(ns subrosa.plugins.fs-logging
  (:use [subrosa.hooks :only [add-hook]]
        [subrosa.client :exclude [io-agent]]
        [subrosa.config :only [config]])
  (:import [java.util Date]
           [java.text SimpleDateFormat]
           [java.io File FileWriter]))

(def io-agent (agent nil))

(defn format-date []
  (.format (SimpleDateFormat. "yyyy-MM-dd") (Date.)))

(defn format-time []
  (.format (SimpleDateFormat. "HH:mm:ss") (Date.)))

(defn get-log-name [room-name]
  (let [log-dir (File. (System/getProperty "user.dir")
                       (config :plugins :fs-logging :directory))]
    (.mkdirs log-dir)
    (str log-dir "/" room-name "_" (format-date) ".log")))

(defn append [room-name msg]
  (send io-agent
        (fn [_]
          (io!
           (with-open [out (FileWriter. (get-log-name room-name) true)]
             (.write out (.toCharArray
                          (str (format-time) " " msg "\n"))))))))

(defmulti log (fn [& args] (first args)))

(defmethod log 'privmsg-room-hook [hook channel room-name msg]
  (if-let [action (second (re-find #"^\u0001ACTION (.*)\u0001$" msg))]
    (append room-name (format "*%s %s" (nick-for-channel channel) action))
    (append room-name (format "<%s> %s" (nick-for-channel channel) msg))))

(defmethod log 'notice-room-hook [hook channel room-name msg]
  (append room-name (format "-%s- %s" (nick-for-channel channel) msg)))

(defmethod log 'join-hook [hook channel room-name]
  (append room-name
          (format "--- join: %s (%s) joined %s"
                  (nick-for-channel channel)
                  (format-client channel)
                  room-name)))

(defmethod log 'part-hook [hook channel room-name]
  (append room-name
          (format "--- part: %s left %s"
                  (nick-for-channel channel)
                  room-name)))

(defmethod log 'quit-hook [hook channel quit-msg]
  (doseq [room-name (rooms-for-nick (nick-for-channel channel))]
    (append room-name
            (format "--- quit: %s (Quit: %s)"
                    (nick-for-channel channel)
                    (or quit-msg "Client Quit")
                    room-name))))

(defmethod log 'nick-hook [hook channel old-nick new-nick]
  (doseq [room-name (rooms-for-nick old-nick)]
    (append room-name
            (format "--- nick: %s is now known as %s" old-nick new-nick))))

(defmethod log 'topic-hook [hook channel room-name old-topic new-topic]
  (append room-name
          (format "--- topic: %s set the topic to \"%s\""
                  (nick-for-channel channel) new-topic)))

(defn add-hooks []
  (add-hook ::logging 'privmsg-room-hook log)
  (add-hook ::logging 'notice-room-hook log)
  (add-hook ::logging 'join-hook log)
  (add-hook ::logging 'part-hook log)
  (add-hook ::logging 'quit-hook log)
  (add-hook ::logging 'nick-hook log)
  (add-hook ::logging 'topic-hook log))

(when (config :plugins :fs-logging :enabled?)
  (add-hooks))
