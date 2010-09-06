(ns subrosa.main
  (:gen-class)
  (use [subrosa.netty :only [create-server]]))

(defn -main [port]
  (let [{:keys [start-fn stop-fn]} (create-server (Integer/parseInt port))]
    (start-fn)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-fn))))
