(defproject subrosa (.trim (slurp "etc/version.txt"))
  :main subrosa.main
  :repositories {"jboss"
                 "http://repository.jboss.org/nexus/content/groups/public/"}
  :resource-paths ["etc"]
  :dependencies [[aleph "0.3.0-beta8"]
                 [log4j "1.2.16"]
                 [org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.4"]
                 [org.clojure/tools.namespace "0.1.3"]
                 [org.jboss.netty/netty "3.2.1.Final"]
                 [slingshot "0.10.3"]
                 [sonian/carica "1.0.1"]
                 [swank-clojure "1.4.3"]]
  :profiles {:dev {:dependencies [[commons-io "2.2"]]}}
  :plugins [[lein-tar "1.1.0"]])
