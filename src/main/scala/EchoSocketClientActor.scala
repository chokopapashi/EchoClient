
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.ReceiveTimeout
import akka.actor.Props

import org.hirosezouen.hzutil._
import HZLog._

trait EchoSocketClientActor extends Actor {
    implicit val logger = getLogger(this.getClass.getName)

    val number: Int
    val localAddr: InetAddress
    val dstSoAddr: InetSocketAddress
    val echoIntarval: Int

    val selector: Selector = Selector.open
    var localSoAddr: InetSocketAddress = _
    var io: SocketChannelIO = _

    implicit val echoName = EchoClientName(number)

    context.setReceiveTimeout(echoIntarval seconds)
}

trait EchoSocketClientActorObject {
    def start(number: Int,
              localAddr: InetAddress,
              dstSoAddr: InetSocketAddress,
              echoIntarval: Int)
             (implicit context: ActorContext): ActorRef
}

class EchoTcpSocketClientActor(
    val number: Int,
    val localAddr: InetAddress,
    val dstSoAddr: InetSocketAddress,
    val echoIntarval: Int)
extends EchoSocketClientActor
{
    var soch: SocketChannel = _

    object ConnectionStart

    override def preStart() {
        log_info(s"${echoName} start.")
        soch = SocketChannel.open
        soch.bind(new InetSocketAddress(localAddr,0))
        localSoAddr = soch.getLocalAddress.asInstanceOf[InetSocketAddress]
        context.parent ! MainActor.PreClientStart(number, self, localSoAddr)
        self ! ConnectionStart
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
        log_info(s"${echoName} restart.")
        TimeUnit.MILLISECONDS.sleep(scala.util.Random.nextInt(5000))
        if(soch != null) {
            soch.close
        }
        preStart
    }

    def receive = {
        case ConnectionStart => {
            log_debug(s"Start connection:Echo${echoName}:$localSoAddr ---> $dstSoAddr")
            catching(classOf[Exception]) either {
                soch.connect(dstSoAddr)
            } match {
                case Right(_) => {
                    log_debug(s"Connection established:${echoName}:$localSoAddr <==> $dstSoAddr")
                    soch.configureBlocking(false)
                    soch.register(selector, SelectionKey.OP_READ)
                    io = new TcpSocketChannelIO(soch, selector, echoName)
                }
                case Left(th) => {
                    log_error(th.toString)
                    throw th
                }
            }
        }
        case ReceiveTimeout => {
            log_trace(s"ReceiveTimeout:${echoName}")
            if(soch.isConnected) {
                val msg = s"${echoName}:${ZonedDateTime.now.toString}"
                io.send(msg.getBytes)
                log_debug(s"Send message:$msg")

                io.recv() match {
                    case Right(bytes) => log_debug(s"Echo message:${new String(bytes)}")
                    case Left(msg) => log_error(msg)
                }
            } else {
                log_debug(s"ReceiveTimeout:${echoName} doesn't connect server yet.")
            }
        }
    }

    override def postStop() {
        log_debug(s"Stop:${echoName}:$localSoAddr x--x $dstSoAddr")
        selector.close
        soch.close
        context.parent ! MainActor.PostClientStop(number, localAddr)
    }
}
object EchoTcpSocketClientActor extends EchoSocketClientActorObject {
    def start(number: Int, localAddr: InetAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new EchoTcpSocketClientActor(number, localAddr, dstSoAddr, echoIntarval)))
    }
}

class EchoUDPSocketClientActor(
    val number: Int,
    val localAddr: InetAddress,
    val dstSoAddr: InetSocketAddress,
    val echoIntarval: Int)
extends EchoSocketClientActor
{
    var dgch: DatagramChannel = _

    override def preStart() {
        log_info(s"${echoName} start.")
        dgch = DatagramChannel.open
        dgch.bind(new InetSocketAddress(localAddr,0))
        dgch.connect(dstSoAddr)
        dgch.configureBlocking(false)
        dgch.register(selector, SelectionKey.OP_READ)
        localSoAddr = dgch.getLocalAddress.asInstanceOf[InetSocketAddress]
        context.parent ! MainActor.PreClientStart(number, self, localSoAddr)
        io = new UDPSocketChannelIO(dgch, selector, echoName)
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
        log_info(s"${echoName} restart.")
        TimeUnit.MILLISECONDS.sleep(scala.util.Random.nextInt(5000))
    }

    def receive = {
        case ReceiveTimeout => {
            log_trace(s"ReceiveTimeout:${echoName}")
            val msg = s"${echoName}:${ZonedDateTime.now.toString}"
            io.send(msg.getBytes)
            log_debug(s"Send message:$msg")

            io.recv() match {
                case Right(bytes) => log_debug(s"Echo message:${new String(bytes)}")
                case Left(msg) => log_error(msg)
            }
        }
    }

    override def postStop() {
        log_debug(s"Stop:${echoName}:$localSoAddr x--x $dstSoAddr")
        selector.close
        dgch.close
        context.parent ! MainActor.PostClientStop(number, localAddr)
    }
}
object EchoUDPSocketClientActor extends EchoSocketClientActorObject {
    def start(number: Int, localAddr: InetAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new EchoUDPSocketClientActor(number, localAddr, dstSoAddr, echoIntarval)))
    }
}

