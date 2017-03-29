
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
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

class EchoSocketClientActor(number: Int, localSoAddr: InetSocketAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int) extends Actor {
    implicit val logger = getLogger(this.getClass.getName)

    var socket: Socket = _
    var out: PrintWriter = _
    var in: BufferedReader = _

    private object ConnectionStart

    override def preStart() {
        socket = new Socket()
        socket.bind(localSoAddr)
        self ! ConnectionStart
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
        TimeUnit.MILLISECONDS.sleep(scala.util.Random.nextInt(5000))
        if(socket != null) {
            socket.close
        }
        preStart
    }

    context.setReceiveTimeout(echoIntarval seconds)

    def receive = {
        case ConnectionStart => {
            log_debug(f"Start connection:Echo$number%03d:$localSoAddr ---> $dstSoAddr")
            catching(classOf[Exception]) either {
                socket.connect(dstSoAddr)
            } match {
                case Right(_) => {
                    log_debug(f"Connection established:Echo$number%03d:$localSoAddr <==> $dstSoAddr")
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()))
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
                }
                case Left(th) => {
                    log_error(th.toString)
                    throw th
                }
            }
        }
        case ReceiveTimeout => {
            log_trace(f"ReceiveTimeout:Echo$number%03d")
            if(socket.isConnected) {
                val msg = f"Echo$number%03d:${ZonedDateTime.now.toString}"
                out.println(msg)
                out.flush
                log_debug(s"Send message:$msg")
                var echo_msg: String = null
                while(in.ready) {
                    val echo_msg = in.readLine
                    log_debug(s"Echo message:$echo_msg")
                }
            } else {
                log_debug(f"ReceiveTimeout:Echo$number%03d:restart.")
                preRestart(null, None)
            }
        }
    }

    override def postStop() {
        log_debug(f"Stop:Echo$number%03d:$localSoAddr x--x $dstSoAddr")
        socket.close
        context.parent ! MainActor.IPAddressRelease(localSoAddr)
    }
}

object EchoSocketClientActor {
    implicit val logger = getLogger(this.getClass.getName)
    def start(number: Int, localSoAddr: InetSocketAddress, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new EchoSocketClientActor(number, localSoAddr, dstSoAddr, echoIntarval)))
    }
}

