(ns subrosa.utils)

(defn interleave-all [c1 c2]
  (lazy-seq
   (let [s1 (seq c1) s2 (seq c2)]
     (when (or s1 s2)
       (cons (first s1) (cons (first s2)
                              (interleave-all (rest s1) (rest s2))))))))

(defn merge-nested [v1 v2]
  (if (and (map? v1) (map? v2))
    (merge-with merge-nested v1 v2)
    v2))

(defmacro with-var-root [bindings & body]
  (if (not (seq bindings))
    `(do ~@body)
    (let [old-value-sym (gensym)]
      `(let [~old-value-sym (.getRoot #'~(first bindings))]
         (alter-var-root #'~(first bindings) (fn [& _#]  ~(second bindings)))
         (try (with-var-root ~(nnext bindings) ~@body)
              (finally
               (alter-var-root #'~(first bindings)
                               (fn [& _#] ~old-value-sym))))))))

(defn load-resource [resource-name]
  (-> (Thread/currentThread)
      (.getContextClassLoader)
      (.getResource resource-name)))
