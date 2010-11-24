(ns subrosa.config
  (:use [subrosa.utils :only [merge-nested load-resource]]))

(defn get-config-map []
  (when-let [url (load-resource "subrosa.clj")]
    (read-string (slurp url))))

(defn config [& ks]
  (reduce get (get-config-map) ks))

(defn config-override
  "Bind over subrosa.config/config to put custom config in tests."
  [override-map]
  (fn [& ks]
    (reduce get (merge-nested (get-config-map) override-map) ks)))
