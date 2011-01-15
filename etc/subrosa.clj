{:logging {:directory "log"}
 :ssl {:keystore nil
       :password nil}
 :password nil
 :catchup-retention 100
 ;; Every <catchup-balance-size> messages the log will attempt to
 ;; balance to the catchup retention size
 :catchup-balance-size 10}
