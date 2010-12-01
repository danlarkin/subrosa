(ns subrosa.netty
  (:use [subrosa.server :only [reset-all-state!]]
        [subrosa.client :only [add-channel! remove-channel! send-to-client
                               send-to-client*]]
        [subrosa.config :only [config]]
        [subrosa.ssl :only [make-ssl-engine]]
        [subrosa.commands :only [dispatch-message quit]]
        [subrosa.plugins :only [load-plugins]]
        [clojure.stacktrace :only [root-cause]])
  (:import [java.net InetSocketAddress]
           [java.util.concurrent Executors]
           [org.jboss.netty.bootstrap ServerBootstrap]
           [org.jboss.netty.channel ChannelUpstreamHandler
            ChannelDownstreamHandler ChannelEvent ChannelState ChannelStateEvent
            ExceptionEvent MessageEvent ChannelFutureListener Channels
            ChannelPipelineFactory]
           [org.jboss.netty.handler.ssl SslHandler]
           [org.jboss.netty.channel.group DefaultChannelGroup]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory]
           [org.jboss.netty.handler.codec.frame Delimiters
            DelimiterBasedFrameDecoder]
           [org.jboss.netty.handler.codec.string StringDecoder StringEncoder]))

;; Some code and a lot of inspiration for the layout of this namespace came from
;; Zach Tellman's awesome aleph project: http://github.com/ztellman/aleph

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
    (if (instance? clojure.contrib.condition.Condition (root-cause evt))
      (let [condition (meta (root-cause evt))
            chan-future-agent
            (condp = (:type condition)
                :client-error (send-to-client
                               (.getChannel evt)
                               (:code condition)
                               (:msg condition))
                :protocol-error (send-to-client*
                                 (.getChannel evt)
                                 (format "ERROR %s" (:msg condition)))
                :client-disconnect (println
                                    "Accessed client data after disconnect."))]
        (when (:disconnect condition)
          (await chan-future-agent)
          (when-let [chan-future @chan-future-agent]
            (.addListener chan-future (ChannelFutureListener/CLOSE)))))
      (when (not (some #{(class (root-cause evt))}
                       #{java.io.IOException
                         java.nio.channels.ClosedChannelException}))
        (println up-or-down "ERROR")
        (.printStackTrace (root-cause evt)))))
  evt)

(defn connect-handler [channel-group pipeline evt]
  (when (and (instance? ChannelStateEvent evt)
             (= (.getState evt) (ChannelState/CONNECTED))
             (.getValue evt))
    (if-let [ssl-handler (.get pipeline "ssl")]
      (doto (.handshake ssl-handler)
        (.addListener (reify ChannelFutureListener
                        (operationComplete [_ channel-future]
                          (if (.isSuccess channel-future)
                            (.add channel-group (.getChannel evt))
                            (-> channel-future
                                .getChannel
                                .close))))))
      (.add channel-group (.getChannel evt)))
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
    (quit (.getChannel evt) ":Client Disconnect" false
          (.isConnected (.getChannel evt)) true)
    (dosync
     (remove-channel! (.getChannel evt))))
  evt)

(defn add-string-codec! [pipeline]
  (doto pipeline
    (.addLast "framer"
              (DelimiterBasedFrameDecoder. 8192 (Delimiters/lineDelimiter)))
    (.addLast "decoder" (StringDecoder.))
    (.addLast "encoder" (StringEncoder.))))

(defn maybe-add-ssl-codec! [pipeline]
  (if (config :ssl :keystore)
    (doto pipeline
      (.addLast "ssl" (SslHandler. (make-ssl-engine) false)))
    pipeline))

(defn add-irc-codec! [pipeline channel-group]
  (doto pipeline
    (.addLast "upstream-error"
              (upstream-stage (partial #'netty-error-handler "UPSTREAM")))
    (.addLast "connect" (upstream-stage
                         (partial #'connect-handler channel-group pipeline)))
    (.addLast "message" (message-stage #'message-handler))
    (.addLast "disconnect" (upstream-stage #'disconnect-handler))
    (.addLast "downstream-error"
              (downstream-stage (partial #'netty-error-handler "DOWNSTREAM")))))

(defn create-server [port]
  (let [channel-factory (NioServerSocketChannelFactory.
                         (Executors/newCachedThreadPool)
                         (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. channel-factory)
        channel-group (DefaultChannelGroup.)]
    (.setPipelineFactory bootstrap (reify ChannelPipelineFactory
                                     (getPipeline [_]
                                       (doto (Channels/pipeline)
                                         maybe-add-ssl-codec!
                                         add-string-codec!
                                         (add-irc-codec! channel-group)))))
    (doto bootstrap
      (.setOption "child.tcpNoDelay" true)
      (.setOption "child.keepAlive" true))
    (load-plugins)
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
