(ns subrosa.plugins.swank
  (:require [carica.core :refer [config]]
            [clojure.tools.logging :as log]
            [swank.swank :as swank]))

(when (config :plugins :swank :enabled?)
  (with-out-str
    (swank/start-repl (config :plugins :swank :port)))
  (log/info "Starting Swank... done."))
