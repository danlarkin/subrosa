(ns subrosa.observable
  (:import [org.jboss.netty.channel Channel])
  (:gen-class :extends java.util.Observable
              :state state
              :init init
              :constructors {[String] []}
              :methods [[ENGAGENOTIFICATIONSIMMEDIATELY
                         [org.jboss.netty.channel.Channel String]
                         void]]))

(defn -init [string]
  [[] string])

(defn -ENGAGENOTIFICATIONSIMMEDIATELY [this channel args]
  (.setChanged this)
  (.notifyObservers this [(.state this) channel args]))

(def get-observable
  (memoize (fn [cmd] (subrosa.observable. cmd))))

(defn add-observer [cmd fn]
  (let [observable (get-observable (.toLowerCase (str cmd)))
        observer (reify java.util.Observer
                   (update [this observable args]
                     (apply fn args)))]
    (.addObserver observable observer)
    observer))

(let [observers (atom {})]
  (defn replace-observer [token cmd fn]
    (let [observable (get-observable (.toLowerCase (str cmd)))]
      (when-let [observer (@observers (str token cmd))]
        (.deleteObserver observable observer))
      (let [observer (add-observer cmd fn)]
        (swap! observers assoc (str token cmd) observer)))))
