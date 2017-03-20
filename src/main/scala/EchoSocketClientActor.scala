
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.ReceiveTimeout
import akka.actor.Props


import org.hirosezouen.hzutil._
import HZLog._

class EchoSocketClientActor(number: Int, dstAddr: InetAddress, dstPort: Int, echoIntarval: Int) extends Actor {
    implicit val logger = getLogger(this.getClass.getName)
    log_trace("EchoSocketClientActor")

    val socket = new Socket()
    var out: PrintWriter = _
    var in: BufferedReader = _

    override def preStart() {
        socket.connect(new InetSocketAddress(dstAddr, dstPort))
        val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()))
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
    }

    context.setReceiveTimeout(echoIntarval seconds)

    def receive = {
        case ReceiveTimeout => {
            val msg = s"Echo$number:${ZonedDateTime.now.toString}"
            out.println(msg)
            out.flush
            log_debug(s"Send message : $msg")
            val echo_msg = in.readLine
            log_debug(s"Echo message : $echo_msg")
        }
    }

    override def postStop() {
        socket.close
    }
}

object EchoSocketClientActor {
    implicit val logger = getLogger(this.getClass.getName)
    def start(number: Int, dstAddr: InetAddress, dstPort: Int, echoIntarval: Int)(implicit context: ActorContext): ActorRef = {
        log_trace("EchoSocketClientActor:start")
        context.actorOf(Props(new EchoSocketClientActor(number, dstAddr, dstPort, echoIntarval)))
    }
}

