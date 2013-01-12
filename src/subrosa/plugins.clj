(ns subrosa.plugins
  (:require [clojure.tools.namespace :refer [find-namespaces-on-classpath]]))

(defn find-plugins []
  (filter #(.startsWith (str %) "subrosa.plugins.")
          (find-namespaces-on-classpath)))

(defn load-plugins []
  (doseq [plugin-ns (find-plugins)]
    (require plugin-ns)))
