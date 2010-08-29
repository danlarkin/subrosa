(ns subrosa.client
  (:use [subrosa.server]
        [subrosa.utils :only [set-conj]]))

(defn authenticated? [channel]
  (not (@+pending-connections+ channel)))

(defn add-channel! [channel]
  (commute +pending-connections+ assoc channel #{}))

(defn remove-channel! [channel]
  (commute +nicks+ dissoc (@+channels+ channel))
  (commute +channels+ dissoc channel)
  (commute +pending-connections+ dissoc channel))

(defn add-user-for-nick! [channel nick]
  (commute +channels+ assoc channel nick))

(defn nick-for-channel [channel]
  (@+channels+ channel))

(defn user-for-nick [nick]
  (@+nicks+ nick))

(defn change-nickname! [old-nick new-nick]
  (let [existing-user (user-for-nick old-nick)]
    (commute +nicks+ dissoc old-nick)
    (commute +nicks+ assoc new-nick existing-user)))

(defn maybe-add-authentication-step! [channel step]
  (when-not (authenticated? channel)
    (commute +pending-connections+ update-in [channel] conj step)))

(defn send-to-client* [channel msg]
  (when (.isWritable channel)
    (send (agent nil)
          (fn [_] (io! (.write channel (str msg "\n")))))))

(defn send-to-client
  ([channel code msg]
     (send-to-client channel code msg (:host +server+)))
  ([channel code msg from]
     (send-to-client*
      channel
      (format ":%s %03d %s %s"
              from code (nick-for-channel channel) msg))))

(defn send-welcome [channel]
  (send-to-client channel 1
                  (format
                   "Welcome to the Internet Relay Network %s"
                   (nick-for-channel channel)))
  (send-to-client channel 2
                  (format
                   "Your host is %s, running version %s"
                   (:host +server+)
                   (:version +server+)))
  (send-to-client channel 3
                  (format "This server was created %s" (:started +server+)))
  (send-to-client channel 4
                  (format "%s %s mMvV bcdefFhiIklmnoPqstv"
                          (:host +server+)
                          (:version +server+))))

(defn maybe-update-authentication! [channel]
  (when (and (not (authenticated? channel))
             (= (@+pending-connections+ channel) #{"NICK" "USER"}))
    (commute +pending-connections+ dissoc channel)
    (send-welcome channel)))

(defn update-user-for-nick! [nick [user-name mode _ real-name]]
  (commute +nicks+ assoc nick {:user-name user-name
                               :real-name real-name}))

(defn format-client [channel]
  (let [nick (nick-for-channel channel)]
    (format "%s!%s@%s"
            nick
            (:user-name (user-for-nick nick))
            (-> channel
                .getRemoteAddress
                .getAddress
                .getCanonicalHostName))))

(defn add-nick-to-room! [nick room-name]
  (commute +rooms+ update-in [room-name :nicks] set-conj nick))

(defn topic-for-room [room-name]
  (get-in @+rooms+ [room-name :topic]))

(defn room-exists? [room-name]
  ((set (keys @+rooms+)) room-name))

(defn nicks-in-room [room-name]
  (get-in @+rooms+ [room-name :nicks]))

(defn all-nicks []
  (keys @+nicks+))
