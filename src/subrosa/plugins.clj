(ns subrosa.plugins
  (:use [clojure.tools.namespace]))

(defn find-plugins []
  (with-out-str (print (find-namespaces-on-classpath)))
  (filter #(.startsWith (str %) "subrosa.plugins.")
          (find-namespaces-on-classpath)))

(defn load-plugins []
  (doseq [plugin-ns (find-plugins)]
    (require plugin-ns)))
