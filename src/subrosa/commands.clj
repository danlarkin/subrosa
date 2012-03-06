(ns subrosa.commands
  (:use [subrosa.client]
        [subrosa.hooks :only [add-hook hooked? run-hook]]
        [subrosa.utils :only [interleave-all]]
        [subrosa.config :only [config]]
        [subrosa.server :only [supported-room-modes]]
        [clojure.string :only [join]]
        [clojure.contrib.condition :only [raise]])
  (:require [clojure.tools.logging :as log])
  (:import [org.jboss.netty.channel ChannelFutureListener]))

(defn dispatch-message [message channel]
  (let [[cmd args] (seq (.split message " " 2))]
    (if (hooked? cmd)
      (run-hook cmd channel (or args ""))
      (do
        (when-not (empty? message)
          (log/debug "Received unhandled command:" message)
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

(defn quit [channel]
  (let [nick (nick-for-channel channel)
        quit-message (or (quit-message-for-channel channel)
                         "Client Disconnect")
        msg (format ":%s QUIT :%s" (format-client channel) quit-message)]
    (send-to-clients-in-rooms-for-nick nick msg channel)
    (run-hook 'quit-hook channel quit-message)))

(defcommand quit [channel quit-message]
  (let [quit-message (if-not (empty? quit-message)
                       (subs quit-message 1)
                       "Client Quit")
        msg (format ":%s QUIT :%s" (format-client channel) quit-message)]
    (dosync
     (add-quit-message-for-channel! channel quit-message))
    (when (.isConnected channel) ; client could send QUIT and then close socket
      (let [chan-future-agent (send-to-client* channel msg)]
        (await chan-future-agent)
        (.addListener @chan-future-agent (ChannelFutureListener/CLOSE))))))

(defcommand join [channel args]
  (let [[rooms keys extra-args] (.split args " ")]
    (if (not extra-args)
      (if (not (empty? rooms))
        (let [rooms (.split rooms ",")
              keys (.split (or keys "") ",")]
          (dosync
           (doseq [[room-name key] (partition-all 2 (interleave-all
                                                     rooms keys))
                   :when (not (nick-in-room? (nick-for-channel channel)
                                             room-name))]
             (if (.startsWith room-name "#")
               (do
                 (add-nick-to-room! (nick-for-channel channel) room-name)
                 (send-to-room room-name (format ":%s JOIN %s"
                                                 (format-client channel)
                                                 room-name))
                 (dispatch-message (format "TOPIC %s" room-name) channel)
                 (dispatch-message (format "NAMES %s" room-name) channel)
                 (run-hook 'join-hook channel room-name))
               (raise {:type :client-error
                       :code 403
                       :msg (format "%s :No such channel" room-name)})))))
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
            (if (nick-in-room? (nick-for-channel channel) room-name)
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
                      :code 442
                      :msg (format "%s :You're not on that channel"
                                   room-name)}))
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
        (let [plain-msg (subs received-msg 1)]
          (if (room-for-name recipient)
            (do
              (send-to-room-except recipient msg channel)
              (run-hook 'privmsg-room-hook
                        channel recipient plain-msg))
            (if-let [channel (channel-for-nick recipient)]
              (do
                (send-to-client* channel msg)
                (run-hook 'privmsg-nick-hook
                          channel recipient plain-msg))
              (raise {:type :client-error
                      :code 401
                      :msg (format "%s :No such nick/channel" recipient)}))))
        (raise {:type :client-error
                :code 412
                :msg ":No text to send"}))
      (raise {:type :client-error
              :code 411
              :msg ":No recipient given (PRIVMSG)"}))))

(defcommand notice [channel args]
  (let [[recipient received-msg] (.split args " " 2)
        msg (format ":%s NOTICE %s %s"
                    (format-client channel) recipient received-msg)]
    (when (and (not (empty? recipient))
               (not (nil? received-msg)))
      (let [plain-msg (subs received-msg 1)]
        (if (room-for-name recipient)
          (do
            (send-to-room-except recipient msg channel)
            (run-hook 'notice-room-hook channel recipient plain-msg))
          (when-let [chan (channel-for-nick recipient)]
            (send-to-client* chan msg)
            (run-hook 'notice-nick-hook chan recipient plain-msg)))))))

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
            rooms (remove room-is-private? (rooms-for-nick nick))
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
    ;; erc sends an erroneous (AFAICT) colon along with its LIST command
    ;; So we'll parse that out here and drop it if present.
    (let [rooms (if (.startsWith rooms ":")
                  (subs rooms 1)
                  rooms)
          rooms (if (empty? rooms)
                  (all-rooms)
                  (.split rooms ","))]
      ;; Technically rfc2812 deprecates message 321,
      ;; however erc won't do channel listing without it.
      ;; Also, yes there should be two spaces between
      ;; ":Users" and "Name"
      (send-to-client channel 321 "Channel :Users  Name")
      (doseq [room-name rooms
              :when (and (room-for-name room-name)
                         (not (room-is-private? room-name)))]
        (send-to-client channel 322 (format "%s %s :%s"
                                            room-name
                                            (count (nicks-in-room room-name))
                                            (or (topic-for-room room-name)
                                                ""))))
      (send-to-client channel 323 ":End of LIST"))))

(defcommand part [channel command]
  (let [[rooms-string part-message] (.split command ":" 2)
        nick (nick-for-channel channel)
        part-message (or part-message nick)
        rooms (map (memfn trim) (.split rooms-string ","))]
    (doseq [room rooms]
      (if (not (empty? room))
        (if (room-for-name room)
          (if (nick-in-room? nick room)
            (dosync
             (send-to-room room (format ":%s PART %s :%s"
                                        (format-client channel)
                                        room
                                        part-message))
             (remove-nick-from-room! nick room)
             (run-hook 'part-hook channel room part-message))
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

(defcommand lusers [channel args]
  (send-lusers channel))

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
                                            (or
                                             (first
                                              (remove room-is-private?
                                                      (rooms-for-nick nick)))
                                             room-name-for-reply)
                                            room-name-for-reply)
                                          user-name
                                          (format-hostname (:channel user))
                                          (hostname)
                                          nick
                                          real-name)))
    (send-to-client channel 315 (format "%s :End of WHO list"
                                        room-name-for-reply))))

(defcommand ison [channel nicks]
  (if (not (empty? nicks))
    (let [nicks (.split nicks " ")
          on-nicks (filter user-for-nick nicks)]
      (send-to-client channel 303 (format ":%s"
                                          (apply str
                                                 (interpose " " on-nicks)))))
    (raise {:type :client-error
            :code 461
            :msg  "ISON :Not enough parameters"})))

(defcommand invite [channel args]
  (let [sender-nick (nick-for-channel channel)
        [target-nick room-name] (.split args " " 2)
        do-invite (fn []
                    (send-to-client* (channel-for-nick target-nick)
                                     (format ":%s INVITE %s %s"
                                             (format-client channel)
                                             target-nick
                                             room-name))
                    (send-to-client (channel-for-nick sender-nick)
                                    341
                                    (format "%s %s" room-name target-nick)))]
    (if (not (or (empty? target-nick)
                 (empty? room-name)))
      (if (user-for-nick target-nick)
        (if (not (nick-in-room? target-nick room-name))
          (if (room-for-name room-name)
            (if (nick-in-room? sender-nick room-name)
              (do-invite)
              (raise {:type :client-error
                      :code 442
                      :msg (format "%s :You're not on that channel"
                                   room-name)}))
            (do-invite))
          (raise {:type :client-error
                  :code 443
                  :msg (format "%s %s :is already on channel"
                               target-nick
                               room-name)}))
        (raise {:type :client-error
                :code 401
                :msg (format "%s :No such nick/channel" target-nick)}))
      (raise {:type :client-error
              :code 461
              :msg  "INVITE :Not enough parameters"}))))

(defcommand kick [channel args]
  (let [[rooms nicks comment] (.split args " " 3)]
    (if (and rooms nicks)
      (let [rooms (.split rooms ",")
            nicks (.split nicks ",")
            comment (or (and comment (subs comment 1))
                        (nick-for-channel channel))
            do-kick (fn [nick room]
                      (if (room-for-name room)
                        (if (nick-in-room? (nick-for-channel channel) room)
                          (if (nick-in-room? nick room)
                            (dosync
                             (send-to-room room (format ":%s KICK %s %s :%s"
                                                        (format-client channel)
                                                        room
                                                        nick
                                                        comment))
                             (remove-nick-from-room! nick room)
                             (run-hook 'kick-hook channel room nick comment))
                            (raise {:type :client-error
                                    :code 441
                                    :msg (format
                                          "%s %s :They aren't on that channel"
                                          nick
                                          room)}))
                          (raise {:type :client-error
                                  :code 442
                                  :msg (format "%s :You're not on that channel"
                                               room)}))
                        (raise {:type :client-error
                                :code 403
                                :msg (format "%s :No such channel" room)})))]
        (cond
         (and (= 1 (count rooms))
              (>= 1 (count nicks)))
         (doseq [nick nicks]
           (do-kick nick
                    (first rooms)))

         (= (count rooms) (count nicks))
         (doseq [[room nick] (zipmap rooms nicks)]
           (do-kick nick room))

         :else (raise {:type :client-error
                       :code 461
                       :msg  "KICK :Not enough parameters"})))
      (raise {:type :client-error
              :code 461
              :msg  "KICK :Not enough parameters"}))))

(defcommand mode [channel args]
  (let [[room-name modes params] (.split args " ")
        user (user-for-nick room-name)
        room (room-for-name room-name)]
    (if (or user room)
      (if room
        (if-not modes
          (send-to-client channel
                          324
                          (format "%s %s"
                                  room-name
                                  (format-mode true (mode-for-room room-name))))
          (let [first-char (.charAt modes 0)
                enable? (not= \- first-char)
                new-mode (set (if (#{\+ \-} first-char)
                                (rest modes)
                                (seq modes)))]
            (if (every? supported-room-modes new-mode)
              (dosync
               (let [room-mode (mode-for-room room-name)]
                 (if enable?
                   (set-mode-for-room! room-name (clojure.set/union room-mode new-mode))
                   (set-mode-for-room! room-name (clojure.set/difference room-mode new-mode)))
                 (when-not (= room-mode (mode-for-room room-name))
                   (send-to-room room-name (format ":%s MODE %s %s"
                                                   (format-client channel)
                                                   room-name
                                                   (format-mode enable? new-mode))))))
              (raise {:type :client-error
                      :code 472
                      :msg (format "%s :is unknown mode char to me for %s"
                                   (first new-mode)
                                   room-name)}))))
        (raise {:type :client-error
                :code 501
                :msg ":Unknown MODE flag"}))
      (raise {:type :client-error
              :code 401
              :msg (format "%s :No such nick/channel" room-name)}))))
