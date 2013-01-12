(ns subrosa.test.expect
  (:require [carica.core :refer [config]]
            [clojure.java.io :refer [delete-file file]]
            [clojure.string :refer [join]]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]
            [subrosa.netty :refer [create-server]])
  (:import (java.io BufferedReader InputStreamReader)
           (java.net SocketTimeoutException)
           (javax.net.ssl X509TrustManager SSLContext)
           (javax.net SocketFactory)
           (org.apache.log4j LogManager Level)))

(def ^:dynamic *timeout* 10000)
(def ^:dynamic *host* "localhost")
(def ^:dynamic *port* 6789)

(defn run-test-server [f]
  (let [server (create-server *port*)]
    (.setLevel (LogManager/getRootLogger) (Level/FATAL))
    ((:start-fn server))
    (f)
    ((:stop-fn server))))

(defn socket-read-line [in]
  (try+
   (let [read (.readLine in)]
     (if (nil? read)
       :timeout
       read))
   (catch SocketTimeoutException e
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
     (try+
      ~@body
      (finally
        (.close (:socket ~s))))))

(defn transmit [socket command]
  (let [out (:out socket)]
    (.write out (.getBytes (str command "\r\n")))
    (.flush out)))

(defn found? [pattern lines]
  (some (partial re-find pattern) lines))

(defn expect [socket pattern]
  (.setSoTimeout (:socket socket) *timeout*)
  (let [in (:in socket)]
    (loop [line (socket-read-line in)]
      (swap! (:received socket) conj (if (= :timeout line)
                                       ""
                                       line))
      (if (found? pattern @(:received socket))
        true
        (if (= :timeout line)
          (join "\n" @(:received socket))
          (recur (socket-read-line in)))))))

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

(defmethod assert-expr 'not-received? [msg form]
  (let [socket (nth form 1)
        string (nth form 2)]
    `(do
       (let [v# (expect ~socket ~string)]
         (if (not (true? v#))
           (report {:type :pass :message ~msg
                    :expected ~string :actual v#})
           (report {:type :fail :message ~msg
                    :expected (str "(not-found? " ~string ")")
                    :actual (str "found " ~string)}))))))
