
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.Future
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

import EchoClientUtil._

class EchoSocketClientActor(number: Int, localAddr: InetAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int) extends Actor {
    implicit val logger = getLogger(this.getClass.getName)

    var soch: SocketChannel = _
    var selector: Selector = _ 
    val sendBuffer = ByteBuffer.wrap(new Array[Byte](64))
    val recvBuffer = ByteBuffer.wrap(new Array[Byte](64))
    var localSoAddr: InetSocketAddress = _

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

    def send(bytes: Array[Byte]) {
        sendBuffer.clear
        sendBuffer.put(bytes)
        sendBuffer.flip

        while(sendBuffer.position < sendBuffer.limit)
            soch.write(sendBuffer)
    }

    def recv(buffer: ByteBuffer): Either[String,Array[Byte]] = {
        val arrayBuffer = mutable.ArrayBuffer.empty[Byte]
        if(0 < selector.select(5000)) {
            val keySet = selector.selectedKeys().asScala
            for(key <- keySet) {
                keySet.remove(key)
                if(key.isReadable) {
                    def recvConcrete() {
                        recvBuffer.clear
                        val ret = key.channel.asInstanceOf[SocketChannel].read(recvBuffer)
                        if(0 < ret) {
                            recvBuffer.flip
                            arrayBuffer ++= recvBuffer.array.take(recvBuffer.limit)
                            recvConcrete()
                        }
                    }
                    recvConcrete()
                } else
                    throw new IllegalStateException(key.toString)
            }
            Right(arrayBuffer.toArray)
        } else
            Left("recv timeout")
    }

    def receive = {
        case ConnectionStart => {
            log_debug(s"Start connection:Echo${echoName(number)}:$localSoAddr ---> $dstSoAddr")
            catching(classOf[Exception]) either {
                soch.connect(dstSoAddr)
            } match {
                case Right(_) => {
                    log_debug(s"Connection established:${echoName(number)}:$localSoAddr <==> $dstSoAddr")
                    soch.configureBlocking(false)
                    soch.register(selector, SelectionKey.OP_READ)
                }
                case Left(th) => {
                    log_error(th.toString)
                    throw th
                }
            }
        }
        case ReceiveTimeout => {
            log_trace(s"ReceiveTimeout:${echoName(number)}")
            if(soch.isConnected) {
                val msg = s"${echoName(number)}:${ZonedDateTime.now.toString}"
                send(msg.getBytes)
                log_debug(s"Send message:$msg")

                recv(recvBuffer) match {
                    case Right(bytes) => log_debug(s"Echo message:${new String(bytes)}")
                    case Left(msg) => log_error(msg)
                }
            } else {
                log_debug(s"ReceiveTimeout:${echoName(number)}:restart.")
                preRestart(null, None)
            }
        }
    }

    override def postStop() {
        log_debug(s"Stop:${echoName(number)}:$localSoAddr x--x $dstSoAddr")
        selector.close
        soch.close
        context.parent ! MainActor.IPAddressRelease(localAddr)
    }
}

object EchoSocketClientActor {
    case class LocalSocketAddressUpdate(number: Int, localSoAddr: InetSocketAddress)

    implicit val logger = getLogger(this.getClass.getName)
    def start(number: Int, localAddr: InetAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new EchoSocketClientActor(number, localAddr, dstSoAddr, echoIntarval)))
    }
}

