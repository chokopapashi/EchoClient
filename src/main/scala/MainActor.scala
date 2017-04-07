
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.control.Exception._

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

import org.hirosezouen.hzactor._
import HZActor._
import org.hirosezouen.hzutil._
import HZLog._
import org.hirosezouen.hznet.InetSocketAddressPool


trait InputCommand
object InputCommand {
    case class ICMD_Start(n: Int) extends InputCommand
    case class ICMD_Stop(n: Int) extends InputCommand
    case class ICMD_StartRange(s: Int, e: Int) extends InputCommand
    case class ICMD_StopRange(s: Int, e: Int) extends InputCommand
    object ICMD_PrintEchoSocketClients extends InputCommand
}

class MyInputActor(in: InputStream, clientName: String) extends InputActor(in, defaultInputFilter) {

    def printCommandsInfo() {
        val commandsInfo =
            s"""|Commands Info :
                |***********************
                | $clientName
                |
                | Comands:
                |   Q         = Quit
                |   S xxx     = Start Echo Client xxx
                |   S xxx-yyy = Start Echo Client range xxx to yyy
                |   E xxx     = Stop Echo Client xxx
                |   E xxx-yyy = Stop Echo Client range xxx to yyy
                |   D         = print EchoSocketClients
                |   h         = print this help
                |
                |   xxx,yyy is the number of Echo Client.
                |***********************
                |""".stripMargin
        log_info(commandsInfo)
    }

    override def preStart() {
        printCommandsInfo
        super.preStart
    }

    val start_r = """(?i)^s (\d+)$""".r
    val stop_r = """(?i)^e (\d+)$""".r
    val start_range_r = """^(?i)s (\d{1,4})-(\d{1,4})$""".r
    val stop_range_r = """^(?i)e (\d{1,4})-(\d{1,4})$""".r
    val quit_r = "(?i)^q$".r
    val printClients_r = "(?i)^d$".r
    val help_r = "(?i)^h$".r

    def execCmd_StartStop(ns: String, cmdFunc: (Int) => InputCommand) {
        catching(classOf[Exception]) opt {Integer.parseInt(ns)} match {
            case Some(n) => context.parent ! cmdFunc(n)
            case None => log_error(s"worng number : $ns")
        }
    }

    def execCmd_StartStopRange(sns: String, ens: String, cmdFunc: (Int,Int) => InputCommand) {
        List((sns,"start"),(ens,"end")).map { tp =>
            catching(classOf[Exception]) either {Integer.parseInt(tp._1)} match {
                case Right(i) => Right(i)
                case Left(_) => Left(s"worng ${tp._2} number : $tp._1")
            }
        } match {
            case List(Right(sn), Right(en)) => context.parent ! cmdFunc(sn,en)
            case xs => xs.foreach { e =>
                e match {
                    case Left(msg) => log_error(msg)
                    case _ => /* Nothing to do.*/
                }
            }
        }
    }

    override val input: PFInput = {
        case start_r(ns) => execCmd_StartStop(ns, (n) => InputCommand.ICMD_Start(n))
        case stop_r(ns)  => execCmd_StartStop(ns, (n) => InputCommand.ICMD_Stop(n))
        case start_range_r(sns,ens) => execCmd_StartStopRange(sns, ens, (ns,ne) => InputCommand.ICMD_StartRange(ns,ne))
        case stop_range_r(sns,ens)  => execCmd_StartStopRange(sns, ens, (ns,ne) => InputCommand.ICMD_StopRange(ns,ne))
        case quit_r() => System.in.close
        case printClients_r() => context.parent ! InputCommand.ICMD_PrintEchoSocketClients
        case help_r() => printCommandsInfo
        case s        => log_info(s"unknown command : $s")
    }
}
object MyInputActor {
    def start(in: InputStream, clientName: String)(implicit context: ActorContext): ActorRef = {
        context.actorOf(Props(new MyInputActor(in, clientName)), "MyInputActor")
    }
}

/* -------------------------------------------------------------------------- */

case class EchoSocketClientState(number: Int, actorRef: ActorRef, localSoAddr: InetSocketAddress)
object EchoSocketClientState {
    type ESCS = EchoSocketClientState

    def apply(localSoAddr: InetSocketAddress, escs: EchoSocketClientState): EchoSocketClientState = {
        EchoSocketClientState(escs.number, escs.actorRef, localSoAddr)
    }
}
class EchoSocketClientStates {
    import EchoSocketClientState.ESCS
    var num2escs = SortedMap.empty[Int,ESCS]
    var actor2escs = Map.empty[ActorRef,ESCS]
    def +=(escs: ESCS) = {
        num2escs += (escs.number -> escs)
        actor2escs += (escs.actorRef -> escs)
    }
    def add(n: Int, ar: ActorRef, soAddr: InetSocketAddress) = +=(EchoSocketClientState(n, ar, soAddr))
    def -=(escs: ESCS) = {
        num2escs -= escs.number
        actor2escs -= escs.actorRef
    }
    def delete(n: Int) = get(n) match {
        case Some(escs) => -=(escs)
        case None => throw new IllegalArgumentException()
    }
    def delete(ar: ActorRef) = get(ar) match {
        case Some(escs) => -=(escs)
        case None => throw new IllegalArgumentException()
    }
    def get(n: Int): Option[ESCS] = num2escs.get(n)
    def get(ar: ActorRef): Option[ESCS] = actor2escs.get(ar)
    def contains(n: Int) = num2escs.contains(n)
    def contains(ar: ActorRef) = actor2escs.contains(ar)
    def valuesBy[B](f: (ESCS) => B): List[B] = num2escs.values.map(escs => f(escs)).toList
    def size = num2escs.size
    override def toString = s"num2escs=$num2escs, actor2escs=$actor2escs"
}
object EchoSocketClientStates {
    type ESCSS = EchoSocketClientStates
}

/* -------------------------------------------------------------------------- */

abstract class MainActor extends Actor {
    val addrPool: InetSocketAddressPool
    val dstSoAddr: InetSocketAddress
    val echoIntarval: Int
    val clientName: String

    def scao: EchoSocketClientActorObject

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=(-1), withinTimeRange=(1 second), loggingEnabled=true) {
//        case _: ConnectException => Restart
        case _: Exception => Stop
        case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    import EchoSocketClientState.ESCS
    import EchoSocketClientStates.ESCSS

    private val escss = new ESCSS

    def startEchoSocketClientActor(n: Int): Unit = escss.get(n) match {
        case Some(_) => log_error(s"${EchoClientName.name(n)} has been running.")
        case None => addrPool.get match {
            case Some(lsa) => {
                Future {
                    TimeUnit.MILLISECONDS.sleep(scala.util.Random.nextInt(2000))   
                    scao.start(n,lsa.getAddress,dstSoAddr,echoIntarval)
                }(context.dispatcher)
            }
            case None => log_error("All IP Address has been used.")
        }
    } 
    def stopEchoSocketClientActor(n: Int): Unit = escss.get(n) match {
        case Some(escs) => {
            context.stop(escs.actorRef)
        }
        case None => log_error(s"${EchoClientName.name(n)} dose not exist.")
    }

    def printClients() {
        val msg = f"%n" + escss.valuesBy(escs => s"${EchoClientName.name(escs.number)}:${escs.localSoAddr}").mkString(f"%n") +
                  f"%nTotal=${escss.size}"
        log_info(msg)
    }
   
    def preClientStart(n: Int, actorRef: ActorRef, soAddr: InetSocketAddress) {
        escss.add(n, actorRef, soAddr)
    }

    def postClientStop(n: Int, addr: InetAddress) {
        escss.delete(n)
        addrPool.release(new InetSocketAddress(addr,0))
    }

    private val actorStates = HZActorStates()

    override def preStart() {
        actorStates += MyInputActor.start(System.in, clientName)
    }

    def receive = {
        case InputCommand.ICMD_Start(n) => startEchoSocketClientActor(n)
        case InputCommand.ICMD_Stop(n) => stopEchoSocketClientActor(n)
        case InputCommand.ICMD_StartRange(s, e) => (s to e) foreach(n => startEchoSocketClientActor(n))
        case InputCommand.ICMD_StopRange(s, e) => (s to e).foreach(n => stopEchoSocketClientActor(n))
        case InputCommand.ICMD_PrintEchoSocketClients => printClients
        case MainActor.PreClientStart(n, actorRef, soAddr) => preClientStart(n, actorRef, soAddr)
        case MainActor.PostClientStop(n,addr) => postClientStop(n, addr)
        case Terminated(actor) if(escss.contains(actor)) => escss.delete(actor)
        case Terminated(actor) if(actorStates.contains(actor)) => {
            actorStates -= actor
            if(actorStates.isEmpty)
                context.system.terminate()
        }
        case x => log_error(s"x=$x")
    }
}
object MainActor {
    case class PreClientStart(number: Int, actorRef: ActorRef, soAddr: InetSocketAddress)
    case class PostClientStop(number: Int, addr: InetAddress)
}

trait MainActorObject {
    def start(addrPool: InetSocketAddressPool,
              dstSoAddr: InetSocketAddress,
              echoIntarval: Int,
              clientName: String)
             (implicit system: ActorRefFactory): ActorRef
}

