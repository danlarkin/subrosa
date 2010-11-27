(ns subrosa.test.expect
  (:use [clojure.test]
        [clojure.string :only [join]]
        [subrosa.netty :only [create-server]]
        [subrosa.config :only [config]])
  (:import [java.io BufferedReader InputStreamReader]
           [javax.net.ssl X509TrustManager SSLContext]
           [javax.net SocketFactory]))

(def *timeout* 10000)
(def *host* "localhost")
(def *port* 6789)

(defn run-test-server [f]
  (let [server (create-server *port*)]
    (try
      (with-out-str ((:start-fn server)))
      #_(Thread/sleep 500)
      (f)
      (with-out-str ((:stop-fn server)))
      #_(Thread/sleep 2000))))

(defn socket-read-line [in]
  (try
    (.readLine in)
    (catch java.net.SocketTimeoutException e
      :timeout)))

(defn create-socketfactory []
  (if (config :ssl :keystore)
    (let [tm (reify X509TrustManager
               (checkClientTrusted [this chain auth-type])
               (checkServerTrusted [this chain auth-type])
               (getAcceptedIssuers [this]))
          context (doto (SSLContext/getInstance "SSL")
                    (.init nil (into-array [tm]) nil))]
      (.getSocketFactory context))
    (SocketFactory/getDefault)))

(defn connect
  ([] (connect *host* *port*))
  ([host port]
     (let [s (doto (.createSocket (create-socketfactory) host port)
               (.setSoTimeout 1000))]
       {:socket s
        :in (BufferedReader. (InputStreamReader. (.getInputStream s)))
        :out (.getOutputStream s)
        :received (atom [])})))

(defmacro with-connection [s & body]
  `(let [~s (connect)]
     (try
       ~@body
       (finally
        (.close (:socket ~s))))))

(defn transmit [socket command]
  (let [out (:out socket)]
    (.write out (.getBytes (str command "\n")))
    (.flush out)))

(defn found? [pattern lines]
  (some (partial re-find pattern) lines))

(defn expect
  ([socket pattern] (expect socket pattern (or *timeout* 0)))
  ([socket pattern timeout]
     (.setSoTimeout (:socket socket) timeout)
     (let [in (:in socket)]
       (loop [line (socket-read-line in)]
         (swap! (:received socket) conj (if (= :timeout line)
                                          ""
                                          line))
         (if (found? pattern @(:received socket))
           true
           (if (= :timeout line)
             (join "\n" @(:received socket))
             (recur (socket-read-line in))))))))

(defmethod assert-expr 'received? [msg form]
  (let [socket (nth form 1)
        string (nth form 2)]
    `(do
       (let [v# (expect ~socket ~string)]
         (if (true? v#)
           (report {:type :pass :message ~msg
                    :expected ~string :actual v#})
           (report {:type :fail :message ~msg
                    :expected ~string :actual v#}))))))
