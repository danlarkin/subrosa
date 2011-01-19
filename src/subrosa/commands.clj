(ns subrosa.commands
  (:use [subrosa.client]
        [subrosa.hooks :only [add-hook hooked? run-hook]]
        [subrosa.utils :only [interleave-all]]
        [subrosa.config :only [config]]
        [clojure.string :only [join]]
        [clojure.contrib.condition :only [raise]])
  (:import [org.jboss.netty.channel ChannelFutureListener])
  (:import [java.util Date]))

(defn dispatch-message [message channel]
  (let [[cmd args] (seq (.split message " " 2))]
    (if (hooked? cmd)
      (run-hook cmd channel (or args ""))
      (do
        (when-not (empty? message)
          (println "Received unhandled command:" message)
          (when (authenticated? channel)
            (raise {:type :client-error
                    :code 421
                    :msg (format "%s :Unknown command" cmd)})))))))

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
  `(add-hook ::commands '~cmd (fn ~@(fix-args false fn-tail))))

(defmacro defcommand
  "Define a command which requires its user to be authenticated."
  [cmd & fn-tail]
  `(add-hook ::commands '~cmd (fn ~@(fix-args true fn-tail))))

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
           (let [msg (format ":%s NICK :%s"
                             (format-client channel) nick)]
             (send-to-client* channel msg)
             (send-to-clients-in-rooms-for-nick (nick-for-channel channel)
                                                msg channel)))
         (run-hook 'nick-hook channel (nick-for-channel channel) nick)
         (change-nickname! channel nick)
         (add-user-for-nick! channel nick)
         (maybe-add-authentication-step! channel "NICK")
         (maybe-update-authentication! channel))
        (raise {:type :client-error
                :code 433
                :msg (format "%s :Nickname is already in use" nick)}))
      (raise {:type :client-error
              :code 432
              :msg (format "%s :Erroneous nickname" nick)}))
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

(defcommand* pass [channel password]
  (if (and (not (authenticated? channel))
           (empty? (authentication-for-channel channel)))
    (if-not (empty? password)
      (if (and (config :password) (= password (config :password)))
        (dosync
         (maybe-add-authentication-step! channel "PASS")
         (maybe-update-authentication! channel))
        (raise {:type :protocol-error
                :disconnect true
                :msg ":Bad Password"}))
      (raise {:type :client-error
              :code 461
              :disconnect true
              :msg  "PASS :Not enough parameters"}))
    (raise {:type :client-error
            :code 462
            :disconnect true
            :msg ":Unauthorized command (already registered)"})))

(defn quit [channel quit-msg close-channel? send-to-self? send-to-others?]
  (let [nick (nick-for-channel channel)
        msg (format ":%s QUIT :Client Quit"
                    (format-client channel))]
    (when send-to-others?
      (send-to-clients-in-rooms-for-nick
       nick msg channel))
    (let [chan-future-agent (when send-to-self?
                              (send-to-client* channel msg))]
      (when chan-future-agent
        (await chan-future-agent))
      (when send-to-others?
        (run-hook 'quit-hook channel "Client Quit"))
      (when (and close-channel? chan-future-agent)
        (.addListener @chan-future-agent (ChannelFutureListener/CLOSE))))))

(defcommand quit [channel quit-msg]
  (quit channel quit-msg true true false))

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
             (dispatch-message (format "NAMES %s" room-name) channel)
             (run-hook 'join-hook channel room-name))))
        (raise {:type :client-error
                :code 461
                :msg "JOIN :Not enough parameters"}))
      (raise {:type :client-error
              :code 461
              :msg "JOIN :Too many parameters"}))))

(defn valid-topic? [topic]
  (.startsWith topic ":"))

(defcommand topic [channel room-name-and-topic]
  (if (not (empty? room-name-and-topic))
    (let [[room-name topic] (.split room-name-and-topic " " 2)]
      (if (room-for-name room-name)
        (if topic
          (if (valid-topic? topic)
            (let [old-topic (topic-for-room room-name)
                  new-topic (subs topic 1)]
              (dosync
               (set-topic-for-room! room-name new-topic)
               (send-to-room room-name (format ":%s TOPIC %s :%s"
                                               (format-client channel)
                                               room-name
                                               new-topic)))
              (run-hook 'topic-hook channel room-name old-topic new-topic))
            (raise {:type :client-error
                    :code 461
                    :msg (format "TOPIC :Not enough parameters")}))
          (if-let [topic (topic-for-room room-name)]
            (send-to-client channel 332
                            (format "%s :%s" room-name topic))
            (send-to-client channel 331
                            (format "%s :No topic is set" room-name))))
        (raise {:type :client-error
                :code 403
                :msg (format "%s :No such channel" room-name)})))
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
          (do
            (send-to-room-except recipient msg channel)
            (run-hook 'privmsg-room-hook
                      channel recipient (if (= (first received-msg) ":")
                                          (subs received-msg 1)
                                          received-msg)))
          (if-let [channel (channel-for-nick recipient)]
            (do
              (send-to-client* channel msg)
              (run-hook 'privmsg-nick-hook
                        channel recipient (if (= (first received-msg) ":")
                                          (subs received-msg 1)
                                          received-msg)))
            (raise {:type :client-error
                    :code 401
                    :msg (format "%s :No such nick/channel" recipient)})))
        (raise {:type :client-error
                :code 412
                :msg ":No text to send"}))
      (raise {:type :client-error
              :code 411
              :msg ":No recipient given (PRIVMSG)"}))))

(defcommand notice [channel args]
  (let [[recipient received-msg] (.split args " " 2)
        msg (format ":%s NOTICE %s %s"
                    (format-client channel) recipient received-msg)
        plain-msg (subs msg 1)]
    (when (and (not (empty? recipient))
               (not (nil? received-msg)))
      (if (room-for-name recipient)
        (do
          (send-to-room-except recipient msg channel)
          (run-hook 'notice-room-hook channel recipient plain-msg))
        (when-let [chan (channel-for-nick recipient)]
          (send-to-client* chan msg)
          (run-hook 'notice-nick-hook chan recipient plain-msg))))))

(defcommand ping [channel server]
  (if (not (empty? server))
    (send-to-client* channel (format "PONG %s :%s" (hostname) server))
    (raise {:type :client-error
            :code 409
            :msg ":No origin specified"})))

(defcommand pong [channel server]
  (when (empty? server)
    (raise {:type :client-error
            :code 409
            :msg ":No origin specified"})))

(defcommand whois [channel username]
  (if-not (empty? username)
    (if-let [user (user-for-nick username)]
      (let [nick (:nick user)
            user-name (:user-name user)
            real-name (:real-name user)
            rooms (rooms-for-nick nick)
            hostname (format-hostname (:channel user))]
        (send-to-client channel 311 (format "%s %s * :%s"
                                            user-name
                                            hostname
                                            real-name))
        (send-to-client channel 319 (format "%s :%s"
                                            user-name
                                            (apply str (interpose " " rooms))))
        (send-to-client channel 318 ":End of WHOIS list"))
      (raise {:type :client-error
              :code 401
              :msg (format "%s :No such nick/channel" username)}))
    (raise {:type :client-error
            :code 431
            :msg ":No nickname given"})))

(defcommand list [channel rooms]
  (when-not (= rooms "STOP")
    (let [rooms (if (empty? rooms)
                  (all-rooms)
                  (.split rooms ","))]
      (doseq [room-name rooms
              :when (room-for-name room-name)]
        (send-to-client channel 322 (format "%s %s :%s"
                                            room-name
                                            (count (nicks-in-room room-name))
                                            (or (topic-for-room room-name)
                                                ""))))
      (send-to-client channel 323 ":End of LIST"))))

(defcommand part [channel command]
  (let [[rooms-string part-message] (.split command ":" 2)
        rooms (map (memfn trim) (.split rooms-string ","))
        nick (nick-for-channel channel)]
    (doseq [room rooms]
      (if (not (empty? room))
        (if (room-for-name room)
          (if (nick-in-room? nick room)
            (dosync
             (send-to-room room (format ":%s PART %s :%s"
                                        (format-client channel)
                                        room
                                        (or part-message nick)))
             (remove-nick-from-room! nick room)
             (run-hook 'part-hook channel room))
            (raise {:type :client-error
                    :code 442
                    :msg (format "%s :You're not on that channel" room)}))
          (raise {:type :client-error
                  :code 403
                  :msg (format "%s :No such channel" room)}))
        (raise {:type :client-error
                :code 461
                :msg "PART :Not enough parameters"})))))

(defcommand motd [channel args]
  (send-motd channel))

(defcommand who [channel room-name]
  (let [room-name-for-reply (if (empty? room-name)
                              "*"
                              room-name)
        users (map user-for-nick (if (empty? room-name)
                                   (all-nicks)
                                   (nicks-in-room room-name)))]
    (doseq [{:keys [nick real-name user-name] :as user} users]
      (send-to-client channel 352 (format "%s %s %s %s %s H :0 %s"
                                          (if (= room-name-for-reply "*")
                                            room-name-for-reply
                                            (first (rooms-for-nick nick)))
                                          user-name
                                          (format-hostname (:channel user))
                                          (hostname)
                                          nick
                                          real-name)))
    (send-to-client channel 315 (format "%s :End of WHO list"
                                        room-name-for-reply))))

(defn- parse-offset-time [time]
  (let [weeks (Integer/parseInt (or (last (re-find #"(\d+)w" time)) "0"))
        days (Integer/parseInt (or (last (re-find #"(\d+)d" time)) "0"))
        hours (Integer/parseInt (or (last (re-find #"(\d+)h" time)) "0"))
        minutes (Integer/parseInt (or (last (re-find #"(\d+)m" time)) "0"))
        seconds (Integer/parseInt (or (last (re-find #"(\d+)s" time)) "0"))
        new-time (- (.getTime (Date.))
                    (+ (* seconds 1000)
                       (* minutes 1000  60)
                       (* hours 1000 60 60)
                       (* days 1000 60 60 24)
                       (* weeks 1000 60 60 24 7)))]
    (str (if (< new-time 0)
           (.getTime (Date.))
           new-time))))

(defcommand catchup [channel args]
  (let [[room time] (.split args " " 2)
        offset-time (if time (parse-offset-time time) nil)
        msgs (get-catchup-log channel room offset-time)
        start (format "PRIVMSG %s :%s sez:" (nick-for-channel channel) (catchup-name))
        end (format "PRIVMSG %s :End of catchup" (nick-for-channel channel))]
    (when (> (count msgs) 0) (dispatch-message start channel))
    (doseq [msg-text msgs]
      (let [m (format "PRIVMSG %s :%s" (nick-for-channel channel) msg-text)]
        (dispatch-message m channel)))
    (when (> (count msgs) 0) (dispatch-message end channel))))
