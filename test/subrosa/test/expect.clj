(ns subrosa.test.expect
  (:use [clojure.test]
        [clojure.string :only [join]])
  (:import [java.io BufferedReader InputStreamReader]))

(def *timeout* 0)
(def *host* nil)
(def *port* nil)

(defn socket-read-line [in]
  (try
    (.readLine in)
    (catch java.net.SocketTimeoutException e
      nil)))

(defn connect
  ([] (connect *host* *port*))
  ([host port]
     (doto (java.net.Socket. host port)
       (.setSoTimeout 1000))))

(defn transmit [socket command]
  (let [out (.getOutputStream socket)]
    (.write out (.getBytes (str command "\n")))
    (.flush out)))

(defn expect
  ([socket pattern] (expect socket pattern (or *timeout* 0)))
  ([socket pattern timeout]
     (.setSoTimeout socket timeout)
     (let [in (BufferedReader.
               (InputStreamReader.
                (.getInputStream socket)))]
       (loop [received []]
         (let [line (socket-read-line in)]
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
