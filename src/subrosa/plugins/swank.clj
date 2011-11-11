(ns subrosa.plugins.swank
  (:use [subrosa.config :only [config]])
  (:require [swank.swank]))

(when (config :plugins :swank :enabled?)
  (print "Starting Swank... ")
  (with-out-str
    (swank.swank/start-repl (config :plugins :swank :port)))
  (println "done."))
