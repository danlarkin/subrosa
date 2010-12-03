(ns subrosa.hooks)

(defonce get-hook
  (comp
   (memoize
    (fn [hook] (atom {})))
   (memfn toLowerCase)
   str))

(defn add-hook [tag hook fn]
  (swap! (get-hook hook) assoc tag fn))

(defn hooked? [hook]
  (not (empty? @(get-hook hook))))

(defn run-hook [hook & args]
  (doseq [[tag listener] @(get-hook hook)]
    (apply listener hook args)))
