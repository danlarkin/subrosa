(ns subrosa.netty
  (:use [subrosa.server :only [+server+ reset-all-state!]]
        [subrosa.client :only [add-channel! remove-channel! send-to-client
                               send-to-client*]]
        [subrosa.commands :only [dispatch-message]])
  (:import [java.net InetSocketAddress]
           [java.util.concurrent Executors]
           [org.jboss.netty.bootstrap ServerBootstrap]
           [org.jboss.netty.channel ChannelUpstreamHandler
            ChannelDownstreamHandler ChannelEvent ChannelState ChannelStateEvent
            ExceptionEvent MessageEvent]
           [org.jboss.netty.channel.group DefaultChannelGroup]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory]
           [org.jboss.netty.handler.codec.frame Delimiters
            DelimiterBasedFrameDecoder]
           [org.jboss.netty.handler.codec.string StringDecoder StringEncoder]))

(defn event->address [event]
  (-> event
      .getChannel
      .getRemoteAddress))

(defn upstream-stage [handler]
  (reify ChannelUpstreamHandler
         (handleUpstream [_ ctx evt]
                         (.sendUpstream ctx (or (handler evt) evt)))))

(defn downstream-stage [handler]
  (reify ChannelDownstreamHandler
         (handleDownstream [_ ctx evt]
                           (.sendDownstream ctx (or (handler evt) evt)))))

(defn message-stage [handler]
  (upstream-stage
   (fn [evt]
     (when (instance? MessageEvent evt)
       (handler evt)))))

(defn netty-error-handler [up-or-down evt]
  (when (instance? ExceptionEvent evt)
    (if (instance? clojure.contrib.condition.Condition (.getCause evt))
      (let [condition (meta (.getCause evt))]
        (when (= :client-error (:type condition))
          (send-to-client
           (.getChannel evt) (:code condition) (:msg condition)))
        (when (= :client-disconnect (:type condition))
          (println "Tried to grab data about a client after disconnect.")))
      (when (not (some #{(class (.getCause evt))}
                       #{;java.io.IOException
                         java.nio.channels.ClosedChannelException}))
        (println up-or-down "ERROR")
        (.printStackTrace (.getCause evt)))))
  evt)

(defn connect-handler [channel-group evt]
  (when (and (instance? ChannelStateEvent evt)
             (= (.getState evt) (ChannelState/CONNECTED))
             (.getValue evt))
    (.add channel-group (.getChannel evt))
    (dosync
     (add-channel! (.getChannel evt))))
  evt)

(defn message-handler [evt]
  (dispatch-message (.getMessage evt) (.getChannel evt))
  evt)

(defn disconnect-handler [evt]
  (when (and (instance? ChannelStateEvent evt)
             (= (.getState evt) (ChannelState/CONNECTED))
             (not (.getValue evt)))
    (dosync
     (remove-channel! (.getChannel evt))))
  evt)

(defn add-string-codec! [pipeline]
  (doto pipeline
    (.addLast "framer"
              (DelimiterBasedFrameDecoder. 8192 (Delimiters/lineDelimiter)))
    (.addLast "decoder" (StringDecoder.))
    (.addLast "encoder" (StringEncoder.))))

(defn add-irc-codec! [pipeline channel-group]
  (doto pipeline
    (.addLast "upstream-error"
              (upstream-stage (partial #'netty-error-handler "UPSTREAM")))
    (.addLast "connect" (upstream-stage
                         (partial #'connect-handler channel-group)))
    (.addLast "message" (message-stage #'message-handler))
    (.addLast "disconnect" (upstream-stage #'disconnect-handler))
    (.addLast "downstream-error"
              (downstream-stage (partial #'netty-error-handler "DOWNSTREAM")))))

(defn create-server [port]
  (let [channel-factory (NioServerSocketChannelFactory.
                         (Executors/newCachedThreadPool)
                         (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. channel-factory)
        pipeline (.getPipeline bootstrap)
        channel-group (DefaultChannelGroup.)]
    (doto pipeline
      add-string-codec!
      (add-irc-codec! channel-group))
    (doto bootstrap
      (.setOption "child.tcpNoDelay" true)
      (.setOption "child.keepAlive" true))
    {:start-fn (fn []
                 (println "Starting Subrosa.")
                 (reset-all-state!)
                 (->> port
                      InetSocketAddress.
                      (.bind bootstrap)
                      (.add channel-group)))
     :stop-fn (fn []
                (println "Shutting down Subrosa.")
                (doseq [channel channel-group]
                  (send-to-client* channel "ERROR :Server going down"))
                (-> channel-group .close .awaitUninterruptibly)
                (.releaseExternalResources channel-factory))}))
