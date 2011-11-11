(defproject subrosa (.trim (slurp "etc/version.txt"))
  :main subrosa.main
  :resources-path "etc"
  :repositories {"jboss"
                 "http://repository.jboss.org/nexus/content/groups/public/"}
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [org.jboss.netty/netty "3.2.1.Final"]
                 [swank-clojure "1.3.2"]]
  :dev-dependencies [[lein-release "1.1.1"]])
