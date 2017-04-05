
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
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

import EchoSocketUtil._

class EchoSocketClientActor(number: Int, localAddr: InetAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int) extends Actor {
    implicit val logger = getLogger(this.getClass.getName)

    var soch: SocketChannel = _
    var selector: Selector = _ 
    var io: SocketChannelIO = _
    var localSoAddr: InetSocketAddress = _
    implicit val echoName = EchoClientName(number)

    private object ConnectionStart

    override def preStart() {
        soch = SocketChannel.open
        soch.bind(new InetSocketAddress(localAddr,0))
        localSoAddr = soch.getLocalAddress.asInstanceOf[InetSocketAddress]
        context.parent ! EchoSocketClientActor.LocalSocketAddressUpdate(number, localSoAddr)
        soch.configureBlocking(true)
        selector = Selector.open
        self ! ConnectionStart
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
        TimeUnit.MILLISECONDS.sleep(scala.util.Random.nextInt(5000))
        if(soch != null) {
            soch.close
        }
        preStart
    }

    context.setReceiveTimeout(echoIntarval seconds)

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
                    io = SocketChannelIO(soch, selector)
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
                log_debug(s"ReceiveTimeout:${echoName}:restart.")
                preRestart(null, None)
            }
        }
    }

    override def postStop() {
        log_debug(s"Stop:${echoName}:$localSoAddr x--x $dstSoAddr")
        selector.close
        soch.close
        context.parent ! MainActor.IPAddressRelease(localAddr)
    }
}

object EchoSocketClientActor {
    implicit val logger = getLogger(this.getClass.getName)

    case class LocalSocketAddressUpdate(number: Int, localSoAddr: InetSocketAddress)

    def start(number: Int, localAddr: InetAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new EchoSocketClientActor(number, localAddr, dstSoAddr, echoIntarval)))
    }
}

