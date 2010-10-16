(ns subrosa.test.expect
  (:use [clojure.test]
        [clojure.string :only [join]]
        [subrosa.netty :only [create-server]])
  (:import [java.io BufferedReader InputStreamReader]))

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
      nil)))

(defn connect
  ([] (connect *host* *port*))
  ([host port]
     (let [s (doto (java.net.Socket. host port)
               (.setSoTimeout 1000))]
       {:socket s
        :in (BufferedReader. (InputStreamReader. (.getInputStream s)))
        :out (.getOutputStream s)})))

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

(defn expect
  ([socket pattern] (expect socket pattern (or *timeout* 0)))
  ([socket pattern timeout]
     (.setSoTimeout (:socket socket) timeout)
     (let [in (:in socket)]
       (loop [received []]
         (let [line (socket-read-line in)]
                                        ; (println "RECEIVED:" line) ;; verbose mode
           (if (nil? line)
             (join "\n" received)
             (if (re-find pattern line)
               true
               (recur (conj received line)))))))))

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
