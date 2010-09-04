(ns subrosa.utils)

(defn interleave-all [c1 c2]
  (lazy-seq
   (let [s1 (seq c1) s2 (seq c2)]
     (when (or s1 s2)
       (cons (first s1) (cons (first s2)
                              (interleave-all (rest s1) (rest s2))))))))
