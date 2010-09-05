(ns subrosa.commands
  (:use [subrosa.client]
        [subrosa.utils :only [interleave-all]]
        [clojure.string :only [join]]
        [clojure.contrib.condition :only [raise]])
  (:import [org.jboss.netty.channel ChannelFutureListener]))

(defmulti dispatch-command (fn [cmd & _] cmd))

(defn dispatch-message [message channel]
  (let [[cmd args] (seq (.split message " " 2))]
    (dispatch-command cmd channel (or args ""))))

(defn fix-args
  [require-auth? fn-tail]
  (let [[f & r] fn-tail]
    `(~(vec (cons '_ f))
      ~(if require-auth?
         `(when (authenticated? ~(first f))
            ~@r)
         `(do ~@r)))))

(defmacro defcommand*
  "Define a command which can be called by unauthenticated users."
  [cmd & fn-tail]
  `(.addMethod dispatch-command ~cmd
               (fn ~@(fix-args false fn-tail))))

(defmacro defcommand
  "Define a command which requires its user to be authenticated."
  [cmd & fn-tail]
  `(.addMethod dispatch-command ~cmd
               (fn ~@(fix-args true fn-tail))))

(defmethod dispatch-command :default [cmd channel args]
  (when (authenticated? channel)
    (raise {:type :client-error
            :code 421
            :msg (format "%s :Unknown command" cmd)})))

(defn valid-nick? [nick]
  (not (re-find #"[^a-zA-Z0-9\-\[\]\'`^{}_]" nick)))

(defcommand* "NICK" [channel nick]
  (if (not (.isEmpty nick))
    (if (valid-nick? nick)
      (if (not (user-for-nick nick))
        (dosync
         (when (authenticated? channel)
           (send-to-client* channel (format ":%s NICK :%s"
                                            (format-client channel) nick)))
         (change-nickname! channel nick)
         (add-user-for-nick! channel nick)
         (maybe-add-authentication-step! channel "NICK")
         (maybe-update-authentication! channel))
        (raise {:type :client-error
                :code 433
                :msg ":Nickname is already in use"}))
      (raise {:type :client-error
              :code 432
              :msg ":Erroneous nickname"}))
    (raise {:type :client-error
            :code 431
            :msg ":No nickname given"})))

(defcommand* "USER" [channel parts]
  (if (not (authenticated? channel))
    (let [parts (.split parts " " 4)]
      (if (= 4 (count parts))
        (dosync
         (update-user-for-nick! (nick-for-channel channel) parts)
         (maybe-add-authentication-step! channel "USER")
         (maybe-update-authentication! channel))
        (raise {:type :client-error
                :code 461
                :msg  "USER :Not enough parameters"})))
    (raise {:type :client-error
            :code 462
            :msg ":Unauthorized command (already registered)"})))

(defcommand "QUIT" [channel quit-msg]
  (-> channel
      (send-to-client* (format ":%s QUIT :Client Quit"
                               (format-client channel)))
      (.addListener (ChannelFutureListener/CLOSE))))

(defcommand "JOIN" [channel args]
  (let [[rooms keys extra-args] (.split args " ")]
    (if (not extra-args)
      (if (not (empty? rooms))
        (let [rooms (.split rooms ",")
              keys (.split (or keys "") ",")]
          (dosync
           (doseq [[room-name key] (partition-all 2 (interleave-all
                                                     rooms keys))]
             (add-nick-to-room! (nick-for-channel channel) room-name)
             (send-to-client* channel (format ":%s JOIN %s"
                                              (format-client channel)
                                              room-name))
             ;; send a JOIN message to all other users in this room
             (dispatch-message (format "TOPIC %s" room-name) channel)
             (dispatch-message (format "NAMES %s" room-name) channel))))
        (raise {:type :client-error
                :code 461
                :msg "JOIN :Not enough parameters"}))
      (raise {:type :client-error
              :code 461
              :msg "JOIN :Too many parameters"}))))

(defcommand "TOPIC" [channel room-name]
  (if (not (empty? room-name))
    (if (room-for-name room-name)
      (if-let [topic (topic-for-room room-name)]
        (send-to-client channel 332 (format "%s :%s" room-name topic))
        (send-to-client channel 331 (format "%s :No topic is set" room-name)))
      (raise {:type :client-error
              :code 403
              :msg (format "%s :No such channel" room-name)}))
    (raise {:type :client-error
            :code 461
            :msg "TOPIC :Not enough parameters"})))

(defcommand "NAMES" [channel room-name]
  (when-let [names (if (empty? room-name)
                     (seq (all-nicks))
                     (seq (nicks-in-room room-name)))]
    (send-to-client channel 353 (format "= %s :%s"
                                        room-name
                                        (join " " names))))
  (send-to-client channel 366 (format "%s :End of NAMES list" room-name)))
