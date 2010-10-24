(ns subrosa.client
  (:use [clojure.contrib.datalog.database :only [add-tuple remove-tuple select]]
        [clojure.contrib.condition :only [raise]]
        [subrosa.server]))

(defn user-for-channel [channel]
  (if-let [user (first (select @db :user {:channel channel}))]
    user
    (raise {:type :client-disconnect})))

(defn nick-for-channel [channel]
  (-> channel user-for-channel :nick))

(defn user-for-nick [nick]
  (first (select @db :user {:nick nick})))

(defn channel-for-nick [nick]
  (-> nick user-for-nick :channel))

(defn authenticated? [channel]
  (-> channel user-for-channel :pending? nil?))

(defn add-channel! [channel]
  (alter db add-tuple :user
         {:nick nil
          :real-name nil
          :user-name nil
          :channel channel
          :pending? #{}}))

(defn remove-channel! [channel]
  (let [user (user-for-channel channel)
        user-in-rooms (select @db :user-in-room {:user-nick (:nick user)})]
    (alter db remove-tuple :user user)
    (doseq [uir user-in-rooms]
      (alter db remove-tuple :user-in-room uir))))

(defn add-user-for-nick! [channel nick]
  (let [user (user-for-channel channel)]
    (alter db remove-tuple :user user)
    (alter db add-tuple :user (assoc user :nick nick))))

(defn change-nickname! [channel new-nick]
  (let [existing-user (user-for-channel channel)]
    (alter db remove-tuple :user existing-user)
    (alter db add-tuple :user (assoc existing-user :nick new-nick))))

(defn maybe-add-authentication-step! [channel step]
  (when-not (authenticated? channel)
    (let [user (user-for-channel channel)]
      (alter db remove-tuple :user user)
      (alter db add-tuple :user (update-in user [:pending?] conj step)))))

(defn hostname []
  (:host server))

(defn send-to-client* [channel msg]
  (when (.isWritable channel)
    (send (agent nil)
          (fn [_] (io! (.write channel (str msg "\n")))))))

(defn send-to-client
  ([channel code msg]
     (send-to-client channel code msg (hostname)))
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
                   (hostname)
                   (:version server)))
  (send-to-client channel 3
                  (format "This server was created %s" (:started server)))
  (send-to-client channel 4
                  (format "%s %s mMvV bcdefFhiIklmnoPqstv"
                          (hostname)
                          (:version server))))

(defn maybe-update-authentication! [channel]
  (let [user (user-for-channel channel)]
    (when (and (not (authenticated? channel))
               (= (:pending? user) #{"NICK" "USER"}))
      (alter db remove-tuple :user user)
      (alter db add-tuple :user (assoc user :pending? nil))
      (send-welcome channel))))

(defn update-user-for-nick! [nick [user-name mode _ real-name]]
  (let [user (user-for-nick nick)]
    (alter db remove-tuple :user user)
    (alter db add-tuple :user (assoc user
                                :user-name user-name
                                :real-name real-name))))

(defn format-client [channel]
  (let [nick (nick-for-channel channel)]
    (format "%s!%s@%s"
            nick
            (:user-name (user-for-nick nick))
            (-> channel
                .getRemoteAddress
                .getAddress
                .getCanonicalHostName))))

(defn nick-in-room? [nick room-name]
  (first (select @db :user-in-room {:user-nick nick
                                    :room-name room-name})))
(defn room-for-name [room-name]
  (first (select @db :room {:name room-name})))

(defn maybe-create-room! [room-name]
  (when-not (room-for-name room-name)
    (alter db add-tuple :room {:name room-name
                               :topic nil})))

(defn add-nick-to-room! [nick room-name]
  (when-not (nick-in-room? nick room-name)
    (maybe-create-room! room-name)
    (alter db add-tuple :user-in-room {:user-nick nick :room-name room-name})))

(defn topic-for-room [room-name]
  (:topic (room-for-name room-name)))

(defn nicks-in-room [room-name]
  (map :user-nick (select @db :user-in-room {:room-name room-name})))

(defn all-nicks []
  (remove nil? (map :nick (select @db :user nil))))

(defn channels-in-room [room-name]
  (for [{:keys [user-nick]} (select @db :user-in-room
                                    {:room-name room-name})]
    (:channel (user-for-nick user-nick))))

(defn send-to-room [room-name msg]
  (doseq [chan (channels-in-room room-name)]
    (send-to-client* chan msg)))

(defn send-to-room-except [room-name msg channel]
  (doseq [chan (channels-in-room room-name)
          :when (not= chan channel)]
    (send-to-client* chan msg)))

(defn all-rooms []
  (map :name (select @db :room nil)))
