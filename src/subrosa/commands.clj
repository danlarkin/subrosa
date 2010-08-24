(ns subrosa.commands
  (:use [subrosa.client]
        [clojure.contrib.condition :only [raise]])
  (:import [org.jboss.netty.channel ChannelFutureListener]))

(defmulti dispatch-command (fn [cmd & _] cmd))

(defn dispatch-message [message socket]
  (let [[cmd args] (seq (.split message " " 2))
        #_(rest (re-find #"^([A-Za-z]+) ?(.*)?" message))]
    (dispatch-command cmd socket args)))

(defn fix-args
  [fn-tail]
  (let [[f & r] fn-tail]
    (if (vector? f)
      (list* (vec (cons '_ f)) r)
      (map fix-args fn-tail))))

(defmacro defcommand [cmd & fn-tail]
  `(.addMethod dispatch-command ~cmd
               (fn ~@(fix-args fn-tail))))

(defmethod dispatch-command :default [cmd channel args]
  (when (authenticated? channel)
    (raise {:type :client-error
            :code 421
            :msg (format "%s :Unknown command" cmd)})))

(defn valid-nick? [nick]
  (not (re-find #"[^a-zA-Z0-9\-\[\]\'`^{}_]" nick)))

(defcommand "NICK" [channel nick]
  (if (valid-nick? nick)
    (if (not (user-for-nick nick))
      (dosync
       (if-let [existing-nick (nick-for-channel channel)]
         (let [existing-user (user-for-nick existing-nick)
               client (format-client channel)]
           (change-nickname! existing-nick nick)
           (send-to-client* channel (format ":%s NICK :%s" client nick)))
         (change-nickname! nil nick))
       (add-user-for-nick! channel nick)
       (maybe-add-authentication-step! channel "NICK")
       (maybe-update-authentication! channel))
      (raise {:type :client-error
              :code 433
              :msg ":Nickname is already in use"}))
    (raise {:type :client-error
            :code 432
            :msg ":Erroneous nickname"})))

(defcommand "USER" [channel parts]
  (dosync
   (update-user-for-nick! (nick-for-channel channel) parts)
   (maybe-add-authentication-step! channel "USER")
   (maybe-update-authentication! channel)))

(defcommand "QUIT" [channel quit-msg]
  (-> channel
      (send-to-client* (format ":%s QUIT :Client Quit"
                               (format-client channel)))
      (.addListener (ChannelFutureListener/CLOSE))))
