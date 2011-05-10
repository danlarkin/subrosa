{:host "localhost"
 :network "Subrosa"
 :catchup {:max-msgs-per-room 100
           :default-catchup-size 40
           :date-format "MM-dd"
           :time-format "HH:mm:ss"}
 :ssl {:keystore nil
       :password nil}
 :password nil
 :plugins {:fs-logging {:enabled? false
                        :directory "log"}}}
