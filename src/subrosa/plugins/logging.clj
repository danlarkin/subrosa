(ns subrosa.plugins.logging
  (:use [subrosa.hooks :only [add-hook]]
        [subrosa.client :exclude [io-agent]]
        [subrosa.config :only [config]])
  (:import [java.util Date]
           [java.text SimpleDateFormat]
           [java.io File FileWriter]))

(def io-agent (agent nil))

(def time-formatter (SimpleDateFormat. "HH:mm:ss"))
(def date-formatter (SimpleDateFormat. "yyyy-MM-dd"))

(defn format-date []
  (.format date-formatter (Date.)))

(defn format-time []
  (.format time-formatter (Date.)))

(defn get-log-name [room-name]
  (let [log-dir (File. (System/getProperty "user.dir")
                       (config :logging :directory))]
    (.mkdirs log-dir)
    (str log-dir "/" room-name "_" (format-date) ".log")))

(defn append [room-name msg]
  (send io-agent
        (fn [_]
          (io!
           (with-open [out (FileWriter. (get-log-name room-name) true)]
             (.write out (.toCharArray
                          (str (format-time) " " msg "\n"))))))))

(defmulti log-dispatch (fn [& args] (first args)))

(defmethod log-dispatch 'privmsg-room-hook [hook channel room-name msg]
  (append room-name (format "<%s> %s" (nick-for-channel channel) msg)))

(defmethod log-dispatch 'join-hook [hook channel room-name]
  (append room-name
          (format "--- join: %s (%s) joined %s"
                  (nick-for-channel channel)
                  (format-client channel)
                  room-name)))

(defmethod log-dispatch 'part-hook [hook channel room-name]
  (append room-name
          (format "--- part: %s left %s"
                  (nick-for-channel channel)
                  room-name)))

(defmethod log-dispatch 'quit-hook [hook channel quit-msg]
  (doseq [room-name (rooms-for-nick (nick-for-channel channel))]
    (append room-name
            (format "--- quit: %s (Quit: %s)"
                    (nick-for-channel channel)
                    (or quit-msg "Client Quit")
                    room-name))))

(defmethod log-dispatch 'nick-hook [hook channel old-nick new-nick]
  (doseq [room-name (rooms-for-nick old-nick)]
    (append room-name
            (format "--- nick: %s is now known as %s" old-nick new-nick))))

(defmethod log-dispatch 'topic-hook [hook channel room-name old-topic new-topic]
  (append room-name
          (format "--- topic: %s set the topic to \"%s\""
                  (nick-for-channel channel) new-topic)))

(defn log [& args]
  (when (config :logging :directory)
    (apply log-dispatch args)))

(add-hook ::logging 'privmsg-room-hook log)
(add-hook ::logging 'join-hook log)
(add-hook ::logging 'part-hook log)
(add-hook ::logging 'quit-hook log)
(add-hook ::logging 'nick-hook log)
(add-hook ::logging 'topic-hook log)
