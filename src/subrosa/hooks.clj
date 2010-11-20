(ns subrosa.hooks)

(def get-hook
  (comp
   (memoize
    (fn [hook]
      (ref {:hook hook
            :listeners #{}})))
   (memfn toLowerCase)
   str))

(defn add-hook [hook fn]
  (dosync
   (commute (get-hook hook) update-in [:listeners] conj fn)))

(defn hooked? [hook]
  (not (empty? (:listeners @(get-hook hook)))))

(defn run-hook [hook & args]
  (doseq [listener (:listeners @(get-hook hook))]
    (apply listener hook args)))
