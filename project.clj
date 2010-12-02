(defproject subrosa (.trim (slurp "version.txt"))
  :main subrosa.main
  :resources-path "etc"
  :repositories {"jboss"
                 "http://repository.jboss.org/nexus/content/groups/public/"}
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.jboss.netty/netty "3.2.1.Final"]]
  :dev-dependencies [[swank-clojure "1.2.1"]
                     [lein-release "1.1.1"]])
