(ns subrosa.hooks)

(defonce hooks (ref {}))

(def format-hook
  (comp (memfn toLowerCase) str))

(defn get-hook [hook]
  (let [hook (format-hook hook)]
    (dosync
     (if-let [hook-map (@hooks hook)]
       hook-map
       (do
         (alter hooks assoc hook {})
         {})))))

(defn add-hook [tag hook fn]
  (dosync
   (alter hooks update-in [(format-hook hook)] assoc tag fn)))

(defn hooked? [hook]
  (not (empty? (get-hook hook))))

(defn run-hook [hook & args]
  (doseq [[tag listener] (get-hook hook)]
    (apply listener hook args)))
