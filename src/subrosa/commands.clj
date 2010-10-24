(ns subrosa.commands
  (:use [subrosa.client]
        [subrosa.utils :only [interleave-all]]
        [clojure.string :only [join]]
        [clojure.contrib.condition :only [raise]])
  (:import [org.jboss.netty.channel ChannelFutureListener]))

(defmulti dispatch-command (fn [cmd & _] cmd))

(defn dispatch-message [message channel]
  (let [[cmd args] (seq (.split message " " 2))]
    (dispatch-command (.toLowerCase cmd) channel (or args ""))))

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
  `(.addMethod dispatch-command ~(.toLowerCase (str cmd))
               (fn ~@(fix-args false fn-tail))))

(defmacro defcommand
  "Define a command which requires its user to be authenticated."
  [cmd & fn-tail]
  `(.addMethod dispatch-command ~(.toLowerCase (str cmd))
               (fn ~@(fix-args true fn-tail))))

(defmethod dispatch-command :default [cmd channel args]
  (when (authenticated? channel)
    (raise {:type :client-error
            :code 421
            :msg (format "%s :Unknown command" cmd)})))

(defn valid-nick-character? [character]
  (and (not (Character/isWhitespace character))
       (not (Character/isISOControl character))
       (not (Character/isSpaceChar character))
       (not-any? #{character} [\@ \! \+ \: \$])))

(defn valid-nick? [nick]
  (every? valid-nick-character? nick))

(defcommand* nick [channel nick]
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

(defcommand* user [channel parts]
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

(defcommand quit [channel quit-msg]
  (let [chan-future-agent (send-to-client* channel
                                           (format ":%s QUIT :Client Quit"
                                                   (format-client channel)))]
    (await chan-future-agent)
    (when-let [chan-future @chan-future-agent]
      (.addListener chan-future (ChannelFutureListener/CLOSE)))))

(defcommand join [channel args]
  (let [[rooms keys extra-args] (.split args " ")]
    (if (not extra-args)
      (if (not (empty? rooms))
        (let [rooms (.split rooms ",")
              keys (.split (or keys "") ",")]
          (dosync
           (doseq [[room-name key] (partition-all 2 (interleave-all
                                                     rooms keys))]
             (add-nick-to-room! (nick-for-channel channel) room-name)
             (send-to-room room-name (format ":%s JOIN %s"
                                             (format-client channel)
                                             room-name))
             (dispatch-message (format "TOPIC %s" room-name) channel)
             (dispatch-message (format "NAMES %s" room-name) channel))))
        (raise {:type :client-error
                :code 461
                :msg "JOIN :Not enough parameters"}))
      (raise {:type :client-error
              :code 461
              :msg "JOIN :Too many parameters"}))))

(defcommand topic [channel room-name]
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

(defcommand names [channel room-name]
  (when-let [names (if (empty? room-name)
                     (seq (all-nicks))
                     (seq (nicks-in-room room-name)))]
    (send-to-client channel 353 (format "= %s :%s"
                                        room-name
                                        (join " " names))))
  (send-to-client channel 366 (format "%s :End of NAMES list" room-name)))

(defcommand privmsg [channel args]
  (let [[recipient received-msg] (.split args " " 2)
        msg (format ":%s PRIVMSG %s %s"
                    (format-client channel) recipient received-msg)]
    (if (not (empty? recipient))
      (if (not (nil? received-msg))
        (if (room-for-name recipient)
          (send-to-room-except recipient msg channel)
          (if-let [channel (channel-for-nick recipient)]
            (send-to-client* channel msg)
            (raise {:type :client-error
                    :code 401
                    :msg (format "%s :No such nick/channel" recipient)})))
        (raise {:type :client-error
                :code 412
                :msg ":No text to send"}))
      (raise {:type :client-error
              :code 411
              :msg ":No recipient given (PRIVMSG)"}))))

(defcommand ping [channel server]
  (if (not (empty? server))
    (send-to-client* channel (format "PONG %s :%s" (hostname) server))
    (raise {:type :client-error
            :code 409
            :msg ":No origin specified"})))

(defcommand list [channel rooms]
  (let [rooms (if (empty? rooms)
                (all-rooms)
                (.split rooms ","))]
    (doseq [room rooms]
      (send-to-client channel 322 (format "%s 0 :%s"
                                          room
                                          (or (topic-for-room room) ""))))
    (send-to-client channel 323 ":End of LIST")))
