
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

//import akka.actor._
import akka.actor.Actor
import akka.actor.ActorContext
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

trait InputCommand
object InputCommand {
    case class ICMD_Start(n: Int)
    case class ICMD_Stop(n: Int)
    case class ICMD_StartRange(s: Int, e: Int)
    case class ICMD_StopRange(s: Int, e: Int)
}

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

    override def preStart() {
        printCommandsInfo
        super.preStart
    }

    val start_r = """(?i)^s (\d+)$""".r
    val quit_r = "(?i)^q$".r
    override val input: PFInput = {
        case start_r(ns) => {
            catching(classOf[Exception]) opt {Integer.parseInt(ns)} match {
                case Some(n) => context.parent ! InputCommand.ICMD_Start(n)
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

class MainActor(addrPool: InetSocketAddressPool, dstSoAddr: InetSocketAddress, echoIntarval: Int) extends Actor {

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=(-1), withinTimeRange=(2 seconds), loggingEnabled=true) {
        case _: SocketTimeoutException => Restart
        case _: Exception => Stop
        case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    private val actorStates = HZActorStates()

    def startEchoSocketClientActor(number: Int, localSoAddr: InetSocketAddress) {
        actorStates += EchoSocketClientActor.start(number, localSoAddr, dstSoAddr, echoIntarval)
    } 

    val start_r = """(?i)^s (\d+)$""".r
    val quit_r = "(?i)^q$".r
    override def preStart() {
        actorStates += MyInputActor.start(System.in)
    }

    def receive = {
        case InputCommand.ICMD_Start(n) => addrPool.get match {
            case Some(lsa) => startEchoSocketClientActor(n, lsa)
            case None => log_error("All IP Address has been used.")
        }
        case MainActor.IPAddressRelease(sa) => addrPool.release(sa)

        case Terminated(actor) if(actorStates.contains(actor)) => {
            context.system.terminate()
        }
        case x => log_error(s"x=$x")
    }
}

object MainActor {
    case class IPAddressRelease(sa: InetSocketAddress)

    def start(addrPool: InetSocketAddressPool, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit system: ActorRefFactory): ActorRef = {
        system.actorOf(Props(new MainActor(addrPool,dstSoAddr,echoIntarval)))
    }
}

