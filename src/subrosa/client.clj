(ns subrosa.client
  (:require [carica.core :refer [config]]
            [clojure.java.io :refer :all]
            [clojure.set :refer [difference]]
            [slingshot.slingshot :refer [throw+]]
            [subrosa.database :as db]
            [subrosa.server :refer :all]))

(defn user-for-channel [channel]
  (db/get :user :channel channel))

(defn nick-for-channel [channel]
  (-> channel user-for-channel :nick))

(defn agent-for-channel [channel]
  (-> channel user-for-channel :agent))

(defn quit-message-for-channel [channel]
  (-> channel user-for-channel :quit-message))

(defn add-quit-message-for-channel! [channel quit-message]
  (let [user (user-for-channel channel)]
    (db/put :user (assoc user :quit-message quit-message))))

(defn user-for-nick [nick]
  (db/get :user :nick nick))

(defn channel-for-nick [nick]
  (-> nick user-for-nick :channel))

(defn all-nicks []
  (remove nil? (map :nick (remove :pending? (db/get :user)))))

(defn all-rooms []
  (map :name (db/get :room)))

(defn authentication-for-channel [channel]
  (:pending? (user-for-channel channel)))

(defn authenticated? [channel]
  (nil? (authentication-for-channel channel)))

(defn add-channel! [channel]
  (db/put :user {:nick nil
                 :real-name nil
                 :user-name nil
                 :quit-message nil
                 :channel channel
                 :agent (agent "jack bauer")
                 :pending? #{}}))

(defn add-user-for-nick! [channel nick]
  (let [user (user-for-channel channel)]
    (db/put :user (assoc user :nick nick))))

(defn change-nickname! [channel new-nick]
  (let [existing-user (user-for-channel channel)]
    (db/put :user (assoc existing-user :nick new-nick))
    (doseq [user-in-room (db/get :user-in-room :user-nick
                                 (:nick existing-user))]
      (db/put :user-in-room (assoc user-in-room :user-nick new-nick)))))

(defn maybe-add-authentication-step! [channel step]
  (when-not (authenticated? channel)
    (let [user (user-for-channel channel)]
      (db/put :user (update-in user [:pending?] conj step)))))

(defn hostname []
  (:host server))

(defn send-to-client* [channel msg]
  (when (and channel (.isWritable channel))
    (send (agent-for-channel channel)
          (fn [_] (io! (.write channel (str msg "\r\n")))))))

(defn send-to-client
  ([channel code msg]
     (send-to-client channel code msg (hostname)))
  ([channel code msg from]
     (send-to-client*
      channel
      (format ":%s %03d %s %s"
              from code (or (nick-for-channel channel) "*") msg))))

(defn send-welcome [channel]
  (send-to-client channel 1
                  (format
                   ":Welcome to the Internet Relay Network %s"
                   (nick-for-channel channel)))
  (send-to-client channel 2
                  (format
                   ":Your host is %s, running version %s"
                   (hostname)
                   (:version server)))
  (send-to-client channel 3
                  (format ":This server was created %s" (:started server)))
  (send-to-client channel 4
                  (format "%s %s mMvV %s"
                          (hostname)
                          (:version server)
                          (apply str supported-room-modes)))
  (send-to-client channel 5
                  (format "NETWORK=%s :are supported on this server"
                          (config :network))))

(defn send-motd [channel]
  (if (.exists (as-file "etc/motd"))
    (send-to-client* channel (slurp "etc/motd"))
    (send-to-client channel 422 ":MOTD File is missing")))

(defn send-lusers [channel]
  (send-to-client channel 251
                  (format ":There are %s users and %s services on %s servers"
                          (count (all-nicks)) 0 1))
  (send-to-client channel 252 (format "%s :operators online" 0))
  (send-to-client channel 253 (format "%s :unknown connections" 0))
  (send-to-client channel 254 (format "%s :channels formed"
                                      (count (all-rooms))))
  (send-to-client channel 255 (format ":I have %s clients and %s servers"
                                      (count (all-nicks)) 0)))

(defn get-required-authentication-steps []
  (if (config :password)
    #{"NICK" "USER" "PASS"}
    #{"NICK" "USER"}))

(defn maybe-update-authentication! [channel]
  (let [user (user-for-channel channel)]
    (when (not (authenticated? channel))
      (if (not (and (config :password) (= (:pending? user) #{"NICK" "USER"})))
        (when (= (:pending? user) (get-required-authentication-steps))
          (db/put :user (assoc user :pending? nil))
          (send-welcome channel)
          (send-motd channel)
          (send-lusers channel))
        (throw+ {:type :protocol-error
                 :disconnect true
                 :msg ":Bad Password"})))))

(defn update-user-for-nick! [nick [user-name mode _ real-name]]
  (let [user (user-for-nick nick)]
    (db/put :user (assoc user
                    :user-name user-name
                    :real-name (subs real-name 1)))))

(defn format-hostname [channel]
  (-> channel
      .getRemoteAddress
      .getAddress
      .getCanonicalHostName))

(defn format-client [channel]
  (let [nick (nick-for-channel channel)]
    (format "%s!%s@%s"
            nick
            (:user-name (user-for-nick nick))
            (format-hostname channel))))

(defn nick-in-room? [nick room-name]
  (db/get :user-in-room [:user-nick :room-name] [nick room-name]))

(defn room-for-name [room-name]
  (db/get :room :name room-name))

(defn rooms-for-nick [nick]
  (map :room-name (db/get :user-in-room :user-nick nick)))

(defn maybe-create-room! [room-name]
  (when-not (room-for-name room-name)
    (db/put :room {:name room-name
                   :topic nil
                   :mode (if (.endsWith room-name "-")
                           #{\p}
                           #{})})))

(defn maybe-delete-room! [room-name]
  (when-not (seq (db/get :user-in-room :room-name room-name))
    (let [room (room-for-name room-name)]
      (db/delete :room (:id room)))))

(defn add-nick-to-room! [nick room-name]
  (when-not (nick-in-room? nick room-name)
    (maybe-create-room! room-name)
    (db/put :user-in-room {:user-nick nick
                           :room-name room-name})))

(defn remove-nick-from-room! [nick room-name]
  (->> (db/get :user-in-room [:user-nick :room-name] [nick room-name])
       :id
       (db/delete :user-in-room))
  (maybe-delete-room! room-name))

(defn remove-channel! [channel]
  (let [user (user-for-channel channel)
        nick (:nick user)]
    (db/delete :user (:id user))
    (doseq [room-name (rooms-for-nick nick)]
      (remove-nick-from-room! nick room-name))))

(defn topic-for-room [room-name]
  (:topic (room-for-name room-name)))

(defn format-mode [enable? mode]
  (str (if enable?
         "+"
         "-")
       (apply str (map str mode))))

(defn mode-for-room [room-name]
  (:mode (room-for-name room-name)))

(defn set-mode-for-room! [room-name mode]
  (let [room (room-for-name room-name)]
    (db/put :room (assoc room
                    :mode mode))))

(defn room-is-private? [room-name]
  ((or (mode-for-room room-name) #{}) \p))

(defn nicks-in-room [room-name]
  (map :user-nick (db/get :user-in-room :room-name room-name)))

(defn channels-in-room [room-name]
  (for [{:keys [user-nick]} (db/get :user-in-room :room-name room-name)]
    (:channel (user-for-nick user-nick))))

(defn send-to-room [room-name msg]
  (doseq [chan (channels-in-room room-name)]
    (send-to-client* chan msg)))

(defn send-to-room-except [room-name msg channel]
  (doseq [chan (channels-in-room room-name)
          :when (not= chan channel)]
    (send-to-client* chan msg)))

(defn send-to-clients-in-rooms-for-nick [nick msg channel]
  (doseq [chan (difference
                (into #{} (for [room-name (rooms-for-nick nick)
                                chan (channels-in-room room-name)]
                            chan))
                #{channel})]
    (send-to-client* chan msg)))

(defn set-topic-for-room! [room-name topic]
  (let [room (room-for-name room-name)]
    (db/put :room (assoc room
                    :topic topic))))
