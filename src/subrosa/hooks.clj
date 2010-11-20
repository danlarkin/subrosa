(ns subrosa.hooks)

(def get-hook
  (comp
   (memoize
    (fn [hook] (atom #{})))
   (memfn toLowerCase)
   str))

(defn add-hook [hook fn]
  (swap! (get-hook hook) conj fn))

(defn hooked? [hook]
  (not (empty? @(get-hook hook))))

(defn run-hook [hook & args]
  (doseq [listener @(get-hook hook)]
    (apply listener hook args)))
