package io.backchat.hookup

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel._
import socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.http._
import collection.JavaConverters._
import websocketx._
import org.jboss.netty.buffer.ChannelBuffers
import akka.util.duration._
import akka.dispatch.{ ExecutionContext, Await, Promise, Future }
import akka.jsr166y.ForkJoinPool
import java.lang.Thread.UncaughtExceptionHandler
import org.jboss.netty.handler.timeout.{ IdleStateAwareChannelHandler, IdleStateEvent, IdleState, IdleStateHandler }
import org.jboss.netty.logging.{ InternalLogger, InternalLoggerFactory }
import java.util.concurrent.atomic.AtomicLong
import io.backchat.hookup.WebSocketServer.MessageAckingHandler
import org.jboss.netty.util.{ Timeout ⇒ NettyTimeout, TimerTask, HashedWheelTimer, CharsetUtil }
import akka.util.{ Duration, Timeout }
import java.net.{ ConnectException, InetSocketAddress, URI }
import java.nio.channels.ClosedChannelException
import net.liftweb.json.JsonAST.JValue
import net.liftweb.json.{JsonParser, DefaultFormats, Formats, parse, render, compact}
import java.io.{Closeable, File}
import java.util.concurrent.{ConcurrentSkipListSet, TimeUnit, Executors}
import reflect.BeanProperty

/**
 * @see [[io.backchat.hookup.WebSocket]]
 */
object WebSocket {

  /**
   * The websocket inbound message handler
   */
  type Receive = PartialFunction[WebSocketInMessage, Unit]

  /**
   * Global logger for a websocket client.
   */
  private val logger = InternalLoggerFactory.getInstance("WebSocket")

  /**
   * This handler takes care of translating websocket frames into [[io.backchat.hookup.WebSocketInMessage]] instances
   * @param handshaker The handshaker to use for this websocket connection
   * @param host The host to connect to.
   * @param wireFormat The wireformat to use for this connection.
   */
  class WebSocketClientHostHandler(handshaker: WebSocketClientHandshaker, host: WebSocketHost)(implicit wireFormat: WireFormat) extends SimpleChannelHandler {
    private val msgCount = new AtomicLong(0)

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case resp: HttpResponse if handshaker.isHandshakeComplete ⇒
          throw new WebSocketException("Unexpected HttpResponse (status=" + resp.getStatus + ", content="
            + resp.getContent.toString(CharsetUtil.UTF_8) + ")")
        case resp: HttpResponse ⇒
          handshaker.finishHandshake(ctx.getChannel, resp)
          host.receive lift Connected

        case f: TextWebSocketFrame ⇒
          val inferred = wireFormat.parseInMessage(f.getText)
          inferred match {
            case a: Ack        ⇒ Channels.fireMessageReceived(ctx, a)
            case a: AckRequest ⇒ Channels.fireMessageReceived(ctx, a)
            case r             ⇒ host.receive lift r
          }
        case f: BinaryWebSocketFrame ⇒ host.receive lift BinaryMessage(f.getBinaryData.array())
        case f: ContinuationWebSocketFrame ⇒
          logger warn "Got a continuation frame this is not (yet) supported"
        case _: PingWebSocketFrame  ⇒ ctx.getChannel.write(new PongWebSocketFrame())
        case _: PongWebSocketFrame  ⇒
        case _: CloseWebSocketFrame ⇒ host.disconnect()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      (e.getCause) match {
        case _: ConnectException if host.isReconnecting  ⇒ // this is expected
        case _: ClosedChannelException if host.isClosing ⇒ // this is expected
        case ex ⇒
          host.receive.lift(Error(Option(e.getCause))) getOrElse logger.error("Oops, something went amiss", e.getCause)
          e.getChannel.close()
      }
    }

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case content: String        ⇒ e.getChannel.write(new TextWebSocketFrame(content))
        case m: JsonMessage         ⇒ sendOutMessage(m, e.getChannel)
        case m: TextMessage         ⇒ sendOutMessage(m, e.getChannel)
        case BinaryMessage(content) ⇒ e.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(content)))
        case Disconnect             ⇒ // ignore here
        case _: WebSocketOutMessage ⇒
        case _                      ⇒ ctx.sendDownstream(e)
      }
    }

    private def sendOutMessage(msg: WebSocketOutMessage with Ackable, channel: Channel) {
      channel.write(new TextWebSocketFrame(wireFormat.render(msg)))
    }

    override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (!host.isClosing || host.isReconnecting) {
        host.reconnect()
      }
    }
  }

  /**
   * Implementation detail
   * the internal represenation of a websocket client.
   *
   * @param client The client to decorate
   * @param executionContext The execution context for futures
   * @param wireFormat The wireformat to use
   */
  private final class WebSocketHost(val client: WebSocket)(implicit executionContext: ExecutionContext, wireFormat: WireFormat) extends WebSocketLike with BroadcastChannel with Connectable with Reconnectable {

    private[this] val normalized = client.settings.uri.normalize()
    private[this] val tgt = if (normalized.getPath == null || normalized.getPath.trim().isEmpty) {
      new URI(normalized.getScheme, normalized.getAuthority, "/", normalized.getQuery, normalized.getFragment)
    } else normalized
    private[this] val protos = if (client.settings.protocols.isEmpty) null else client.settings.protocols.mkString(",")

    private[this] val bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
    private[this] var handshaker: WebSocketClientHandshaker = null
    private[this] val timer = new HashedWheelTimer()
    private[this] var channel: Channel = null
    private[this] var _isConnected: Promise[OperationResult] = Promise[OperationResult]()
    private[this] val buffer = client.settings.buffer

    def isConnected = channel != null && channel.isConnected && _isConnected.isCompleted

    private def configureBootstrap() {
      val self = this
      val ping = client.settings.pinging.duration.toSeconds.toInt
      bootstrap.setPipelineFactory(new ChannelPipelineFactory {
        def getPipeline = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("timeouts", new IdleStateHandler(timer, ping, 0, 0))
          pipeline.addLast("pingpong", new PingPongHandler(logger))
          if (client.settings.version == WebSocketVersion.V00)
            pipeline.addLast("decoder", new WebSocketHttpResponseDecoder)
          else
            pipeline.addLast("decoder", new HttpResponseDecoder)

          pipeline.addLast("encoder", new HttpRequestEncoder)
          pipeline.addLast("ws-handler", new WebSocketClientHostHandler(handshaker, self))
          pipeline.addLast("acking", new MessageAckingHandler(logger, client.raiseEvents))
          if (client.raiseEvents) {
            pipeline.addLast("eventsHook", new SimpleChannelHandler {
              override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
                e.getMessage match {
                  case m: Ack        ⇒ client.receive lift m
                  case m: AckRequest ⇒ client.receive lift m
                  case _             ⇒ ctx.sendUpstream(e)
                }
              }
            })
          }
          pipeline
        }
      })
    }

    var throttle = client.settings.throttle
    var isClosing = false
    configureBootstrap()

    def connect(): Future[OperationResult] = synchronized {
      handshaker = new WebSocketClientHandshakerFactory().newHandshaker(tgt, client.settings.version, protos, false, client.settings.initialHeaders.asJava)
      isClosing = false
      val self = this
      if (isConnected) Promise.successful(Success)
      else {
        val fut = bootstrap.connect(new InetSocketAddress(client.settings.uri.getHost, client.settings.uri.getPort))
        fut.addListener(new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
             if (future.isSuccess && isReconnecting) {
              future.getChannel.getPipeline.replace("ws-handler", "ws-handler", new WebSocketClientHostHandler(handshaker, self))
            }
          }
        })
        val af = fut.toAkkaFuture flatMap {
          case Success ⇒
            throttle = client.settings.throttle
            channel = fut.getChannel
            handshaker.handshake(channel).toAkkaFuture
          case x ⇒ Promise.successful(x)
        }

        try {

          val fut = af flatMap { _ ⇒
            isReconnecting = false
            _isConnected
          }

          buffer foreach (_.open())
          Await.ready(af, 5 seconds)
        } catch {
          case ex ⇒ {
            logger.error("Couldn't connect, killing all")
            reconnect()
          }
        }
      }
    }

    var isReconnecting = false

    def reconnect(): Future[OperationResult] = {
      if (!isReconnecting) client.receive lift Reconnecting
      isReconnecting = true
      disconnect() andThen {
        case _ ⇒
          delay {
            connect()
          }
      }
    }

    def delay(thunk: ⇒ Future[OperationResult]): Future[OperationResult] = {
      if (client.settings.throttle != NoThrottle) {
        val promise = Promise[OperationResult]()
        val theDelay = throttle.delay
        throttle = throttle.next()
        if (throttle != NoThrottle)
          timer.newTimeout(task(promise, theDelay, thunk), theDelay.toMillis, TimeUnit.MILLISECONDS)
        else promise.success(Cancelled)
        promise
      } else Promise.successful(Cancelled)
    }

    private def task(promise: Promise[OperationResult], theDelay: Duration, thunk: ⇒ Future[OperationResult]): TimerTask = {
      logger info "Connection to host [%s] lost, reconnecting in %s".format(client.settings.uri.toASCIIString, humanize(theDelay))
      new TimerTask {
        def run(timeout: NettyTimeout) {
          if (!timeout.isCancelled) {
            val rr = thunk
            rr onComplete {
              case Left(ex) ⇒ delay(thunk)
              case Right(x) ⇒ promise.success(x)
            }
          } else promise.success(Cancelled)
        }
      }
    }

    private def humanize(dur: Duration) = {
      if (dur.toMillis < 1000)
        dur.toMillis.toString + " milliseconds"
      else if (dur.toSeconds < 60)
        dur.toSeconds.toString + " seconds"
      else if (dur.toMinutes < 60)
        dur.toMinutes.toString + " minutes"
    }

    def disconnect(): Future[OperationResult] = synchronized {
      isClosing = true;
      val closing = Promise[OperationResult]()
      val disconnected = Promise[OperationResult]()

      if (isConnected) {
        channel.write(new CloseWebSocketFrame()).addListener(new ChannelFutureListener {
          def operationComplete(future: ChannelFuture) {
            future.getChannel.close().addListener(new ChannelFutureListener {
              def operationComplete(future: ChannelFuture) {
                if (!isReconnecting) {
                  buffer foreach (_.close())
                  client.receive lift Disconnected(None)
                }
                _isConnected = Promise[OperationResult]()
                disconnected.success(Success)
              }
            })
          }
        })
      } else {
        if (!isReconnecting) {
          buffer foreach (_.close())
          client.receive lift Disconnected(None)
        }
        disconnected.success(Success)
      }

      disconnected onComplete {
        case _ ⇒ {
          _isConnected = Promise[OperationResult]()
          try {
            if (!isReconnecting && bootstrap != null) {
              val thread = new Thread {
                override def run = {
                  bootstrap.releaseExternalResources()
                }
              }
              thread.setDaemon(false)
              thread.start()
              thread.join()
            }
          } catch {
            case e ⇒ logger.error("error while closing the connection", e)
          } finally {
            if (!closing.isCompleted) closing.success(Success)
          }
        }
      }

      closing
    }

    def id = if (channel == null) 0 else channel.id

    def send(message: WebSocketOutMessage): Future[OperationResult] = {
      if (isConnected) {
        channel.write(message).toAkkaFuture
      } else {
        logger info "buffering message until fully connected"
        buffer foreach (_.write(message))
        Promise.successful(Success)
      }
    }

    def receive: Receive = internalReceive orElse client.receive

    def internalReceive: Receive = {
      case Connected ⇒ {
        buffer foreach { b ⇒
          b.drain(channel.send(_)) onComplete {
            case _ ⇒
              _isConnected.success(Success)
          }
          client.receive lift Connected
        }
        if (buffer.isEmpty) {
          _isConnected.success(Success)
          client.receive lift Connected
        }
      }
    }

  }

  /**
   * Fix bug in standard HttpResponseDecoder for web socket clients. When status 101 is received for Hybi00, there are 16
   * bytes of contents expected
   */
  class WebSocketHttpResponseDecoder extends HttpResponseDecoder {

    val codes = List(101, 200, 204, 205, 304)

    protected override def isContentAlwaysEmpty(msg: HttpMessage) = {
      msg match {
        case res: HttpResponse ⇒ codes contains res.getStatus.getCode
        case _                 ⇒ false
      }
    }
  }

  /**
   * The default execution context for the websocket library.
   * it uses a ForkJoinPool as underlying threadpool.
   */
  implicit val executionContext =
    ExecutionContext.fromExecutorService(new ForkJoinPool(
      Runtime.getRuntime.availableProcessors(),
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,
      new UncaughtExceptionHandler {
        def uncaughtException(t: Thread, e: Throwable) {
          e.printStackTrace()
        }
      },
      true))

  /**
   * A WebSocket related exception
   *
   * Copied from [[https://github.com/cgbystrom/netty-tools]]
   */
  class WebSocketException(s: String, th: Throwable) extends java.io.IOException(s, th) {
    def this(s: String) = this(s, null)
  }

  /**
   * Handler to send pings when no data has been sent or received within the timeout.
   * @param logger A logger for this handler to use.
   */
  class PingPongHandler(logger: InternalLogger) extends IdleStateAwareChannelHandler {

    override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
      if (e.getState == IdleState.READER_IDLE || e.getState == IdleState.WRITER_IDLE) {
        if (e.getChannel.isConnected) e.getChannel.write(new PingWebSocketFrame())
      }
    }
  }

  /**
   * A factory method to create a default websocket implementation that uses the specified `recv` as message handler.
   * This probably not the most useful factory method ever unless you can keep the client around somehow too.
   *
   * @param context The configuration for the websocket client
   * @param recv The message handler
   * @param jsFormat the lift-json formats
   * @param wireFormat the [[io.backchat.hookup.WireFormat]] to use
   * @return a [[io.backchat.hookup.DefaultWebSocket]]
   */
  def apply(context: WebSocketContext)
           (recv: Receive)
           (implicit jsFormat: Formats = DefaultFormats, wireFormat: WireFormat = new JsonProtocolWireFormat()(DefaultFormats)): WebSocket = {
    new DefaultWebSocket(context, wireFormat, jsFormat) {
      val receive = recv
    }
  }

  /**
   * A factory method for the java api. It creates a JavaWebSocket which is a websocket with added helpers for
   * the java language, so they too can enjoy this library.
   *
   * @param context The configuration for the websocket client
   * @param jsFormat the lift-json formats
   * @param wireFormat the [[io.backchat.hookup.WireFormat]] to use
   * @return a [[io.backchat.hookup.JavaWebSocket]]
   */
  def create(context: WebSocketContext, jsFormat: Formats, wireFormat: WireFormat): JavaWebSocket =
    new JavaWebSocket(context, wireFormat, jsFormat)

  /**
   * A factory method for the java api. It creates a JavaWebSocket which is a websocket with added helpers for
   * the java language, so they too can enjoy this library.
   *
   * @param context The configuration for the websocket client
   * @param wireFormat the [[io.backchat.hookup.WireFormat]] to use
   * @return a [[io.backchat.hookup.JavaWebSocket]]
   */
  def create(context: WebSocketContext, wireFormat: WireFormat): JavaWebSocket =
    new JavaWebSocket(context, wireFormat)

  /**
   * A factory method for the java api. It creates a JavaWebSocket which is a websocket with added helpers for
   * the java language, so they too can enjoy this library.
   *
   * @param context The configuration for the websocket client
   * @param jsFormat the lift-json formats
   * @return a [[io.backchat.hookup.JavaWebSocket]]
   */
  def create(context: WebSocketContext, jsFormat: Formats): JavaWebSocket =
    new JavaWebSocket(context, jsFormat)

  /**
   * A factory method for the java api. It creates a JavaWebSocket which is a websocket with added helpers for
   * the java language, so they too can enjoy this library.
   *
   * @param context The configuration for the websocket client
   * @return a [[io.backchat.hookup.JavaWebSocket]]
   */
  def create(context: WebSocketContext): JavaWebSocket =
    new JavaWebSocket(context)

}

/**
 * A trait describing a client that can connect to something
 */
trait Connectable { self: BroadcastChannelLike ⇒

  /**
   * A flag indicating connection status.
   * @return a boolean indicating connection status
   */
  @BeanProperty
  def isConnected: Boolean

  /**
   * Connect to the server
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def connect(): Future[OperationResult]
}

/**
 * A trait describing a client that can reconnect to a server.
 */
trait Reconnectable {

  /**
   * Reconnect to the server
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def reconnect(): Future[OperationResult]
}

/**
 * A trait describing an entity that can handle inbound messages
 */
trait WebSocketLike extends BroadcastChannelLike {

  /**
   * Handle inbound [[io.backchat.hookup.WebSocketInMessage]] instances
   * @return a [[io.backchat.hookup.WebSocket.Receive]] as message handler
   */
  def receive: WebSocket.Receive
}

/**
 * The configuration of a websocket client.
 *
 * @param uri The [[java.net.URI]] to connect to.
 * @param version The version of the websocket handshake to use, defaults to the most recent version.
 * @param initialHeaders The headers to send along with the handshake request.
 * @param protocols The protocols this websocket client can understand
 * @param pinging The timeout for pinging.
 * @param buffer The buffer to use when the connection to the server is lost.
 * @param throttle The throttle to use as reconnection schedule.
 * @param executionContext The execution context for futures.
 */
case class WebSocketContext(
  @BeanProperty
  uri: URI,
  @BeanProperty
  version: WebSocketVersion = WebSocketVersion.V13,
  @BeanProperty
  initialHeaders: Map[String, String] = Map.empty,
  @BeanProperty
  protocols: Seq[String] = Nil,
  @BeanProperty
  pinging: Timeout = Timeout(60 seconds),
  @BeanProperty
  buffer: Option[BackupBuffer] = None,
  @BeanProperty
  throttle: Throttle = NoThrottle,
  @BeanProperty
  executionContext: ExecutionContext = WebSocket.executionContext)

/**
 * Usage of the simple websocket client:
 *
 * <pre>
 *   new WebSocket {
 *     val uri = new URI("ws://localhost:8080/thesocket")
 *
 *     def receive = {
 *       case Disconnected(_) ⇒ println("The websocket to " + uri.toASCIIString + " disconnected.")
 *       case TextMessage(message) ⇒ {
 *         println("RECV: " + message)
 *         send("ECHO: " + message)
 *       }
 *     }
 *
 *     connect() onSuccess {
 *       case Success ⇒
 *         println("The websocket to " + uri.toASCIIString + "is connected.")
 *       case _ ⇒
 *     }
 *   }
 * </pre>
 */
trait WebSocket extends WebSocketLike with Connectable with Reconnectable with Closeable {

  import WebSocket.WebSocketHost

  /**
   * The configuration of this client.
   * @return The [[io.backchat.hookup.WebSocketContext]] as configuration object
   */
  def settings: WebSocketContext

  /**
   * A flag indicating whether this websocket client can fallback to buffering.
   * @return whether this websocket client can fallback to buffering or not.
   */
  def buffered: Boolean = settings.buffer.isDefined

  private[hookup] def raiseEvents: Boolean = false

  /**
   * The execution context for futures within this client.
   * @return The [[akka.dispatch.ExecutionContext]]
   */
  implicit protected def executionContext: ExecutionContext = settings.executionContext

  /**
   * The lift-json formats to use when serializing json values.
   * @return The [[net.liftweb.json.Formats]]
   */
  implicit protected def jsonFormats: Formats = DefaultFormats

  /**
   * The wireformat to use when sending messages over the connection.
   * @return the [[io.backchat.hookup.WireFormat]]
   */
  implicit def wireFormat: WireFormat = new JsonProtocolWireFormat

  private[hookup] lazy val channel: BroadcastChannel with Connectable with Reconnectable = new WebSocketHost(this)

  def isConnected: Boolean = channel.isConnected

  /**
   * Send a message to the server.
   *
   * @param message The [[io.backchat.hookup.WebSocketOutMessage]] to send
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def !(message: WebSocketOutMessage) = send(message)

  /**
   * Connect to the server.
   *
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def connect(): Future[OperationResult] = channel.connect()

  /**
   * Reconnect to the server.
   *
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def reconnect(): Future[OperationResult] = channel.reconnect()

  /**
   * Disconnect from the server.
   *
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def disconnect(): Future[OperationResult] = channel.disconnect()


  final def close(): Unit = {
    Await.ready(disconnect(), 30 seconds)
  }

  /**
   * Send a message to the server.
   *
   * @param message The [[io.backchat.hookup.WebSocketOutMessage]] to send
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  final def send(message: WebSocketOutMessage): Future[OperationResult] = channel.send(message)

}

/**
 * Usage of the simple websocket client:
 *
 * <pre>
 *   new DefaultWebSocket(WebSocketContext(new URI("ws://localhost:8080/thesocket"))) {
 *
 *     def receive = {
 *       case Disconnected(_) ⇒ println("The websocket to " + uri.toASCIIString + " disconnected.")
 *       case TextMessage(message) ⇒ {
 *         println("RECV: " + message)
 *         send("ECHO: " + message)
 *       }
 *     }
 *
 *     connect() onSuccess {
 *       case Success ⇒
 *         println("The websocket is connected.")
 *       case _ ⇒
 *     }
 *   }
 * </pre>
 */
abstract class DefaultWebSocket(val settings: WebSocketContext, wf: WireFormat, jsFormat: Formats) extends WebSocket {

  def this(settings: WebSocketContext, wf: WireFormat) = this(settings, wf, DefaultFormats)
  def this(settings: WebSocketContext) = this(settings, new JsonProtocolWireFormat()(DefaultFormats), DefaultFormats)
  def this(settings: WebSocketContext, jsFormats: Formats) =
    this(settings, new JsonProtocolWireFormat()(jsFormats), jsFormats)


  override implicit protected val jsonFormats = jsFormat

  override implicit val wireFormat = wf

}

/**
 * A base class for the java api to listen for websocket events.
 */
trait WebSocketListener {
  /**
   * The callback method for when the client is connected
   *
   * @param client The client that connected.
   */
  def onConnected(client: WebSocket): Unit = ()

  /**
   * The callback method for when the client is reconnecting
   *
   * @param client The client that is reconnecting.
   */
  def onReconnecting(client: WebSocket): Unit = ()

  /**
   * The callback method for when the client is disconnected
   *
   * @param client The client that disconnected.
   */
  def onDisconnected(client: WebSocket, reason: Throwable): Unit = ()

  /**
   * The callback method for when a text message has been received.
   *
   * @param client The client that received the message
   * @param text The message it received
   */
  def onTextMessage(client: WebSocket, text: String): Unit = ()

  /**
   * The callback method for when a json message has been received.
   *
   * @param client The client that received the message
   * @param json The message it received
   */
  def onJsonMessage(client: WebSocket, json: String): Unit = ()

  /**
   * The callback method for when an error has occured
   *
   * @param client The client that received the message
   * @param error The message it received the throwable if any, otherwise null
   */
  def onError(client: WebSocket, reason: Throwable): Unit = ()

  /**
   * The callback method for when a text message has failed to be acknowledged.
   *
   * @param client The client that received the message
   * @param text The message it received
   */
  def onTextAckFailed(client: WebSocket, text: String): Unit = ()

  /**
   * The callback method for when a json message has failed to be acknowledged.
   *
   * @param client The client that received the message
   * @param text The message it received
   */
  def onJsonAckFailed(client: WebSocket, json: String): Unit = ()
}

/**
 * A mixin for a [[io.backchat.hookup.WebSocket]] with helper methods for the java api.
 * When mixed into a websocket it is a full implementation that notifies the registered
 * [[io.backchat.hookup.WebSocketListener]] instances when events occur.
 */
trait JavaHelpers extends WebSocketListener { self: WebSocket =>

  /**
   * Send a text message. If the message is a json string it will still be turned into a json message
   *
   * @param message The message to send.
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def send(message: String): Future[OperationResult] = channel.send(message)

  /**
   * Send a json message.
   *
   * @param message The message to send.
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def send(json: JValue): Future[OperationResult] = channel.send(json)

  /**
   * Send a json message. If the message isn't a json string it will throw a [[net.liftweb.json.JsonParser.ParseException]]
   *
   * @param message The message to send.
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendJson(json: String): Future[OperationResult] = channel.send(JsonParser.parse(json))

  /**
   * Send a text message which expects an Ack. If the message is a json string it will still be turned into a json message
   *
   * @param message The message to send.
   * @param timeout the [[akka.util.Duration]] as timeout for the ack operation
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendAcked(message: String, timeout: Duration): Future[OperationResult] = channel.send(message.needsAck(timeout))
  /**
   * Send a json message which expects an Ack. If the message isn't a json string it will throw a [[net.liftweb.json.JsonParser.ParseException]]
   *
   * @param message The message to send.
   * @param timeout the [[akka.util.Duration]] as timeout for the ack operation
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendAcked(message: JValue, timeout: Duration): Future[OperationResult] = channel.send(message.needsAck(timeout))

  /**
   * Send a text message which expects an Ack. If the message is a json string it will still be turned into a json message
   *
   * @param message The message to send.
   * @param timeout the [[akka.util.Duration]] as timeout for the ack operation
   * @return A [[akka.dispatch.Future]] with the [[io.backchat.hookup.OperationResult]]
   */
  def sendJsonAcked(json: String, timeout: Duration): Future[OperationResult] = channel.send(parse(json).needsAck(timeout))

  private[this] val listeners = new ConcurrentSkipListSet[WebSocketListener]()

  /**
   * Add a listener for websocket events, if you want to remove the listener at a later time you need to keep the instance around.
   * @param listener The [[io.backchat.hookup.WebSocketListener]] to add
   * @return this to allow for chaining
   */
  def addListener(listener: WebSocketListener): this.type = {
    listeners.add(listener)
    this
  }

  /**
   * Remove a listener for websocket events
   * @param listener The [[io.backchat.hookup.WebSocketListener]] to add
   * @return this to allow for chaining
   */
  def removeListener(listener: WebSocketListener): this.type = {
    listeners.remove(listener)
    this
  }

  /**
   * The implementation of the receive handler for java clients.
   * it notfies the listeners by iterating over all of them and calling the designated method.
   * @return The [[io.backchat.hookup.WebSocket.Receive]] message handler
   */
  def receive: WebSocket.Receive = {
    case Connected =>
      listeners.asScala foreach (_.onConnected(this))
      onConnected(this)
    case Disconnected(reason) =>
      listeners.asScala foreach (_.onDisconnected(this, reason.orNull))
      onDisconnected(this, reason.orNull)
    case Reconnecting =>
      listeners.asScala foreach (_.onReconnecting(this))
      onReconnecting(this)
    case TextMessage(text) =>
      listeners.asScala foreach (_.onTextMessage(this, text))
      onTextMessage(this, text)
    case JsonMessage(text) =>
      val str = compact(render(text))
      listeners.asScala foreach (_.onJsonMessage(this, str))
      onJsonMessage(this, str)
    case AckFailed(TextMessage(text)) =>
      listeners.asScala foreach (_.onTextAckFailed(this, text))
      onTextAckFailed(this, text)
    case AckFailed(JsonMessage(json)) =>
      val str = compact(render(json))
      listeners.asScala foreach (_.onJsonAckFailed(this, str))
      onJsonAckFailed(this, str)
    case Error(reason) =>
      listeners.asScala foreach (_.onError(this, reason.orNull))
      onError(this, reason.orNull)
  }
}

/**
 * A java friendly websocket
 * @see [[io.backchat.hookup.WebSocket]]
 * @see [[io.backchat.hookup.JavaHelpers]]
 *
 * @param settings The settings to use when creating this websocket.
 * @param wf The wireformat for this websocket
 * @param jsFormat the lift-json formats
 */
class JavaWebSocket(settings: WebSocketContext, wf: WireFormat, jsFormat: Formats)
  extends DefaultWebSocket(settings, wf, jsFormat) with JavaHelpers {

  /**
   * A java friendly websocket
   * @see [[io.backchat.hookup.WebSocket]]
   * @see [[io.backchat.hookup.JavaHelpers]]
   *
   * @param settings The settings to use when creating this websocket.
   * @param wf The wireformat for this websocket
   */
  def this(settings: WebSocketContext, wf: WireFormat) = this(settings, wf, DefaultFormats)

  /**
   * A java friendly websocket
   * @see [[io.backchat.hookup.WebSocket]]
   * @see [[io.backchat.hookup.JavaHelpers]]
   *
   * @param settings The settings to use when creating this websocket.
   */
  def this(settings: WebSocketContext) = this(settings, new JsonProtocolWireFormat()(DefaultFormats), DefaultFormats)

  /**
   * A java friendly websocket
   * @see [[io.backchat.hookup.WebSocket]]
   * @see [[io.backchat.hookup.JavaHelpers]]
   *
   * @param settings The settings to use when creating this websocket.
   * @param jsFormats the lift-json formats
   */
  def this(settings: WebSocketContext, jsFormats: Formats) =
    this(settings, new JsonProtocolWireFormat()(jsFormats), jsFormats)

}

//trait BufferedWebSocket { self: WebSocket ⇒
//
//  override def throttle = IndefiniteThrottle(500 millis, 30 minutes)
//
//  override def bufferPath = new File("./work/buffer.log")
//  override def buffered = true
//
//}