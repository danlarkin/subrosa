(ns subrosa.client
  (:use [clojure.contrib.datalog.database :only [add-tuple remove-tuple select]]
        [clojure.contrib.condition :only [raise]]
        [subrosa.server]
        [subrosa.config :only [config]])
  (:import [java.util Date]
           [java.text SimpleDateFormat]))

(def io-agent (agent nil))

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

(defn authentication-for-channel [channel]
  (:pending? (user-for-channel channel)))

(defn authenticated? [channel]
  (nil? (authentication-for-channel channel)))

(defn add-channel! [channel]
  (alter db add-tuple :user
         {:nick nil
          :real-name nil
          :user-name nil
          :channel channel
          :pending? #{}
          :login-time (.getTime (Date.))}))

(defn add-user-for-nick! [channel nick]
  (let [user (user-for-channel channel)]
    (alter db remove-tuple :user user)
    (alter db add-tuple :user (assoc user :nick nick))))

(defn change-nickname! [channel new-nick]
  (let [existing-user (user-for-channel channel)]
    (alter db remove-tuple :user existing-user)
    (alter db add-tuple :user (assoc existing-user :nick new-nick))
    (doseq [user-in-room (select @db :user-in-room
                                 {:user-nick (:nick existing-user)})]
      (alter db remove-tuple :user-in-room user-in-room)
      (alter db add-tuple :user-in-room
             (assoc user-in-room :user-nick new-nick)))))

(defn maybe-add-authentication-step! [channel step]
  (when-not (authenticated? channel)
    (let [user (user-for-channel channel)]
      (alter db remove-tuple :user user)
      (alter db add-tuple :user (update-in user [:pending?] conj step)))))

(defn hostname []
  (:host server))

(defn send-to-client* [channel msg]
  (when (.isWritable channel)
    (send io-agent
          (fn [_] (io! (.write channel (str msg "\n")))))))

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
                  (format "%s %s mMvV bcdefFhiIklmnoPqstv"
                          (hostname)
                          (:version server))))

(defn send-motd [channel]
  (send-to-client channel 422 ":MOTD File is missing"))

(defn get-expired-messages
  "Return the messages that are expired, expects the messages to be
  passed in sorted."
  [msgs]
  (let [db-msg-count (count msgs)]
    (when (> db-msg-count (config :catchup-retention))
      (take (- db-msg-count (config :catchup-retention)) msgs))))

(defn format-catchup-time
  "Format the catchup message time to a human-readable format."
  [seconds]
  (.format (SimpleDateFormat. "HH:mm:ss") (Date. seconds)))

(def balance-timer (atom (config :catchup-balance-size)))

(defn add-and-balance-catchup-log
  "Add the message to the catchup log and, if required, balance the log
  to the retention size."
  [nick room msg]
  (dosync
   (alter db add-tuple :message
          {:time (.getTime (Date.))
           :room room
           :text (str msg)
           :nick nick}))
  ;; Decrement timer
  (swap! balance-timer dec)
  (when (<= @balance-timer 0)
    (let [msgs (sort-by :time (select @db :message nil))
          expired-msgs (get-expired-messages msgs)]
      ;; A message may be put in between the filtering and removing
      ;; action, but it's an un-important edge case
      (dosync
       (doseq [m expired-msgs]
         (alter db remove-tuple :message m))))
    (reset! balance-timer (config :catchup-balance-size))))

;; Format used by catchup for the private user to see the logs in
(def catchup-format "%s: <%s> %s")

(defn catchup-name
  "Taken from:
  wikipedia.org/wiki/List_of_historians#Historians_of_the_Ancient_Period"
  []
  (rand-nth
   ["Herodotus" "Thucydides" "Berossus" "Xenophon" "Ptolemy" "Timaeus" "Quintus"
    "Gaius" "Polybius" "Sima Qian" "Diodorus Siculus" "Sallust" "Ban Gu"
    "Flavius Josephus" "Ban Zhao" "Thallus" "Plutarch" "Suetonius" "Appian"
    "Arrian" "Lucius Ampelius" "Dio Cassius" "Herodian" "Ammianus Marcellinus"
    "Philostorgius" "Fa Hien" "Theodoret" "Priscus" "Sozomen" "Salvian"]))

(defn filter-catchup-messages
  "Given a message filter function, return the messages they missed
  for a given room"
  [room msg-filter]
  (->> (select @db :message nil)
       (filter (fn [m] (= room (:room m))))
       (sort-by :time)
       (filter msg-filter)
       (map (fn [m] (format catchup-format
                           (format-catchup-time (:time m))
                           (:nick m)
                           (:text m))))
       (remove nil?)
       doall))

(defn get-catchup-log
  "Retrieve the catchup messages for a given channel room and time. If time
  is nil, use the login time of the user instead."
  [channel room time]
  (when-not (and room (> (count room) 0))
    (raise {:type :client-error
            :code 999
            :msg ":Bad Catchup Room"}))
  (let [user (user-for-channel channel)
        time (when (and time (> (count time) 0))
               (try (long (Long/parseLong time))
                    (catch NumberFormatException _
                      (raise {:type :client-error
                              :code 998
                              :msg ":Bad Catchup Time"}))))
        ;; Filter messages by either a given time, or the time the
        ;; user first logged into the system
        msgs (if time
               (filter-catchup-messages
                room
                (fn [m] (or (= time 0)
                           (> (:time m) time))))
               (filter-catchup-messages
                room
                (fn [m] (< (:time m) (:login-time user)))))]
    msgs))

(defn get-required-authentication-steps []
  (if (config :password)
    #{"NICK" "USER" "PASS"}
    #{"NICK" "USER"}))

(defn maybe-update-authentication! [channel]
  (let [user (user-for-channel channel)]
    (when (not (authenticated? channel))
      (if (not (and (config :password) (= (:pending? user) #{"NICK" "USER"})))
        (when (= (:pending? user) (get-required-authentication-steps))
          (alter db remove-tuple :user user)
          (alter db add-tuple :user (assoc user :pending? nil))
          (send-welcome channel)
          (send-motd channel))
        (raise {:type :protocol-error
                :disconnect true
                :msg ":Bad Password"})))))

(defn update-user-for-nick! [nick [user-name mode _ real-name]]
  (let [user (user-for-nick nick)]
    (alter db remove-tuple :user user)
    (alter db add-tuple :user (assoc user
                                :user-name user-name
                                :real-name (subs real-name 1)
                                :login-time (.getTime (Date.))))))

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
  (first (select @db :user-in-room {:user-nick nick
                                    :room-name room-name})))
(defn room-for-name [room-name]
  (first (select @db :room {:name room-name})))

(defn rooms-for-nick [nick]
  (map :room-name (select @db :user-in-room {:user-nick nick})))

(defn maybe-create-room! [room-name]
  (when-not (room-for-name room-name)
    (alter db add-tuple :room {:name room-name
                               :topic nil})))

(defn maybe-delete-room! [room-name]
  (when-not (seq (select @db :user-in-room {:room-name room-name}))
    (let [room (room-for-name room-name)]
      (alter db remove-tuple :room room))))

(defn add-nick-to-room! [nick room-name]
  (when-not (nick-in-room? nick room-name)
    (maybe-create-room! room-name)
    (alter db add-tuple :user-in-room {:user-nick nick :room-name room-name})))

(defn remove-nick-from-room! [nick room-name]
  (alter db remove-tuple :user-in-room {:user-nick nick :room-name room-name})
  (maybe-delete-room! room-name))

(defn remove-channel! [channel]
  (let [user (user-for-channel channel)
        nick (:nick user)]
    (alter db remove-tuple :user user)
    (doseq [room-name (rooms-for-nick nick)]
      (remove-nick-from-room! nick room-name))))

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

(defn send-to-clients-in-rooms-for-nick [nick msg channel]
  (doseq [chan (into #{} (for [room-name (rooms-for-nick nick)
                               chan (channels-in-room room-name)
                               :when (and chan (not= chan channel))]
                           chan))]
    (send-to-client* chan msg)))

(defn all-rooms []
  (map :name (select @db :room nil)))

(defn set-topic-for-room! [room-name topic]
  (let [room (room-for-name room-name)]
    (alter db remove-tuple :room room)
    (alter db add-tuple :room (assoc room
                                :topic topic))))
