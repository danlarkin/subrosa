(defproject subrosa "1.0.0-SNAPSHOT"
  :main subrosa.main
  :aot [subrosa.observable]
  :repositories {"jboss"
                 "http://repository.jboss.org/nexus/content/groups/public/"}
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.jboss.netty/netty "3.2.1.Final"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [lein-release "1.1.1"]])
