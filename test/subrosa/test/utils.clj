(ns subrosa.test.utils
  (:use [subrosa.netty :only [create-server]]
        [subrosa.test.expect :only [*timeout* *host* *port*]]))

(def test-server-port 6789)

(defn run-test-server [f]
  (let [server (create-server test-server-port)]
    (try
      ((:start-fn server))
      #_(Thread/sleep 500)
      (f)
      ((:stop-fn server))
      #_(Thread/sleep 2000))))

(defn setup-bindings [f]
  (binding [*timeout* 3000
            *host* "localhost"
            *port* test-server-port]
    (f)))
