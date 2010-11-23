(ns subrosa.ssl
  (:use [subrosa.config :only [config]])
  (:import [java.io FileInputStream]
           [javax.net.ssl SSLContext KeyManagerFactory]
           [java.security KeyStore]))

(defn make-keystore []
  (let [ks (KeyStore/getInstance "JKS")]
    (with-open [is (FileInputStream. (config :ssl :keystore))]
      (.load ks is (.toCharArray "foobar")))
    ks))

(defn make-keymanagerfactory []
  (doto (KeyManagerFactory/getInstance "SunX509")
    (.init (make-keystore) (.toCharArray "foobar"))))

(defn make-ssl-context []
  (doto (SSLContext/getInstance "SSL")
    (.init (.getKeyManagers (make-keymanagerfactory))
           nil nil)))

(defn make-ssl-engine []
  (let [engine (.createSSLEngine (make-ssl-context))]
    (.setUseClientMode engine false)
    (.setEnabledCipherSuites engine (.getSupportedCipherSuites engine))
    engine))
