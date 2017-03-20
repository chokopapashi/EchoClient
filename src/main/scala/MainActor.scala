
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

//import akka.actor._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy.Escalate
import akka.actor.SupervisorStrategy.Restart
import akka.actor.SupervisorStrategy.Stop
import akka.actor.Terminated

import org.hirosezouen.hzutil._
import HZLog._

import org.hirosezouen.hzactor._
import HZActor._

import org.hirosezouen.hznet.InetSocketAddressPool


class MyInputActor(in: InputStream) extends InputActor(in, defaultInputFilter) {

    def printCommandsInfo() {
        val commandsInfo =
            """|Commands Info :
               |***********************
               | EchoClient
               |
               | Comands:
               |   Q     = Quit
               |   S xxx = Start Echo Client xxx
               |   E xxx = Stop Echo Client xxx
               |
               |   xxx is the number of Echo Client.
               |***********************
               |""".stripMargin
        log_info(commandsInfo)
    }

    val start_r = """(?i)^s (\d+)$""".r
    val quit_r = "(?i)^q$".r
    override val input: PFInput = {
        case start_r(ns) => {
            catching(classOf[Exception]) opt {Integer.parseInt(ns)} match {
                case Some(n) => addrPool.get match {
                    case Some(sa) => {
                        log_info(s"start Echo$n : $sa"  )
                        startEchoSocketClientActor(n, sa)
                    }
                    case None => log_error("All IP Address has been used.")
                }
                case None => log_error(s"worng number : $ns")
            }
        }
        case quit_r() => System.in.close
        case s        => log_info(s"input : $s")
    }
}
object MyInputActor {
    def start(in: InputStream)(implicit context: ActorContext): ActorRef
        = context.actorOf(Props(new MyInputActor(in)), "MyInputActor")
}

class MainActor(addrPool: InetSocketAddressPool, dstPort: Int, echoIntarval: Int) extends Actor {
    log_trace("MainActor")

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=(-1), withinTimeRange=(2 seconds), loggingEnabled=true) {
        case _: SocketTimeoutException => Restart
        case _: Exception => Stop
        case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    private val actorStates = HZActorStates()

    def startEchoSocketClientActor(number: Int, sockAddr: InetSocketAddress) {
        actorStates += EchoSocketClientActor.start(number, sockAddr.getAddress, dstPort, echoIntarval)
    } 

    val start_r = """(?i)^s (\d+)$""".r
    val quit_r = "(?i)^q$".r
    override def preStart() {
        actorStates += MyInputActor.start(System.in)
    }

    def receive = {
        case Terminated(actor) if(actorStates.contains(actor)) => {
            log_debug(s"MainActor:receive:Terminated($actor)")
            context.system.terminate()
        }
        case x => log_debug(s"x=$x")
    }
}

object MainActor {

    case class IPAddressRelease(ia: InetAddress)

    def start(addrPool: InetSocketAddressPool, dstPort: Int, echoIntarval: Int)(implicit system: ActorRefFactory): ActorRef = {
        log_debug("MainActor:start")
        system.actorOf(Props(new MainActor(addrPool,dstPort,echoIntarval)))
    }
}

