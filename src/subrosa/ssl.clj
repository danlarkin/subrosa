(ns subrosa.ssl
  (:require [carica.core :refer [config]])
  (:import (java.io FileInputStream)
           (java.security KeyStore)
           (javax.net.ssl KeyManagerFactory SSLContext)))

(defn make-keystore []
  (let [ks (KeyStore/getInstance "JKS")]
    (with-open [is (FileInputStream. (config :ssl :keystore))]
      (.load ks is (.toCharArray (config :ssl :password))))
    ks))

(defn make-keymanagerfactory []
  (doto (KeyManagerFactory/getInstance "SunX509")
    (.init (make-keystore) (.toCharArray (config :ssl :password)))))

(defn make-ssl-context []
  (doto (SSLContext/getInstance "SSL")
    (.init (.getKeyManagers (make-keymanagerfactory))
           nil nil)))

(defn make-ssl-engine []
  (let [engine (.createSSLEngine (make-ssl-context))]
    (.setUseClientMode engine false)
    (.setEnabledCipherSuites engine (.getSupportedCipherSuites engine))
    engine))
