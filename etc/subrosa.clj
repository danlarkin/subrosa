{:host "localhost"
 :network "Subrosa"
 :ssl {:keystore nil
       :password nil}
 :password nil
 :plugins {:fs-logging {:enabled? false
                        :directory "log"}
           :catchup {:enabled? true
                     :ignore-non-chat-msgs true
                     :max-msgs-per-room 20
                     :default-playback-size 10}}}
