(ns subrosa.config
  (:use [subrosa.utils :only [load-resource]]))

(defn config [& ks]
  (when-let [url (load-resource "subrosa.clj")]
    (reduce get (read-string (slurp url)) ks)))
