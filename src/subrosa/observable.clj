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
  [[] (ref string)])

(defn -ENGAGENOTIFICATIONSIMMEDIATELY [this channel args]
  (.setChanged this)
  (.notifyObservers this [@(.state this) channel args]))

(def get-observable
  (memoize (fn [cmd] (subrosa.observable. cmd))))
