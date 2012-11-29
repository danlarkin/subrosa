(defproject subrosa/subrosa (.trim (slurp "etc/version.txt")) 
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.4"]
                 [org.clojure/tools.namespace "0.1.3"]
                 [slingshot "0.10.3"]
                 [log4j "1.2.16"]
                 [org.jboss.netty/netty "3.2.1.Final"]
                 [swank-clojure "1.3.2"]]
  :profiles {:dev {:dependencies [[commons-io/commons-io "2.2"]]}}
  :repositories {"jboss"
                 "http://repository.jboss.org/nexus/content/groups/public/"}
  :resource-paths ["etc"]
  :main subrosa.main
  :min-lein-version "2.0.0"
  :plugins [[lein-tar "1.1.0"]])
