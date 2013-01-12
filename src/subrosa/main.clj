(ns subrosa.main
  (:gen-class)
  (:require [carica.core :refer [config]]
            [clojure.tools.logging :as log]
            [subrosa.netty :refer [create-server]]))

(defn -main []
  (let [{:keys [start-fn stop-fn]} (create-server (config :port))]
    (start-fn)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-fn))))
