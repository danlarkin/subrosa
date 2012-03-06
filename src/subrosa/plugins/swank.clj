(ns subrosa.plugins.swank
  (:use [subrosa.config :only [config]])
  (:require [swank.swank]
            [clojure.tools.logging :as log]))

(when (config :plugins :swank :enabled?)
  (with-out-str
    (swank.swank/start-repl (config :plugins :swank :port)))
  (log/info "Starting Swank... done."))
