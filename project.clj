(defproject subrosa (.trim (slurp "etc/version.txt"))
  :main subrosa.main
  :resources-path "etc"
  :repositories {"jboss"
                 "http://repository.jboss.org/nexus/content/groups/public/"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.4"]
                 [org.clojure/tools.namespace "0.1.3"]
                 [slingshot "0.10.3"]
                 [log4j "1.2.16"]
                 [org.jboss.netty/netty "3.2.1.Final"]
                 [swank-clojure "1.3.2"]]
  :dev-dependencies [[lein-tar "1.0.5"]
                     [vimclojure/server "2.3.5"]
                     [lein-eclipse "1.0.0"]
                     [org.clojars.scott/lein-nailgun "1.1.0"]])
