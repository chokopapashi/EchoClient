
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress

import scala.collection.immutable.SortedMap
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.OneForOneStrategy
import akka.actor.Props
import akka.actor.SupervisorStrategy.Escalate
import akka.actor.SupervisorStrategy.Restart
import akka.actor.SupervisorStrategy.Stop
import akka.actor.Terminated

import com.typesafe.config.ConfigFactory

import org.hirosezouen.hzactor._
import HZActor._
import org.hirosezouen.hzutil._
import HZLog._
import org.hirosezouen.hznet.InetSocketAddressPool

import EchoClientUtil._

trait InputCommand
object InputCommand {
    case class ICMD_Start(n: Int) extends InputCommand
    case class ICMD_Stop(n: Int) extends InputCommand
    case class ICMD_StartRange(s: Int, e: Int) extends InputCommand
    case class ICMD_StopRange(s: Int, e: Int) extends InputCommand
    object ICMD_PrintEchoSocketClients extends InputCommand
}

class MyInputActor(in: InputStream) extends InputActor(in, defaultInputFilter) {

    def printCommandsInfo() {
        val commandsInfo =
            """|Commands Info :
               |***********************
               | EchoClient
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
    def start(in: InputStream)(implicit context: ActorContext): ActorRef
        = context.actorOf(Props(new MyInputActor(in)), "MyInputActor")
}

case class EchoSocketClientState(number: Int, actorRef: ActorRef, localSoAddr: InetSocketAddress)
object EchoSocketClientState {
    type ESCS = EchoSocketClientState

    def apply(localSoAddr: InetSocketAddress, escs: EchoSocketClientState): EchoSocketClientState = {
        EchoSocketClientState(escs.number, escs.actorRef, localSoAddr)
    }
}
class EchoSocketClientStates {
    import EchoSocketClientState.ESCS
    var actor2num = SortedMap.empty[ActorRef,ESCS]
    var num2actor = SortedMap.empty[Int,ESCS]
    def +=(escs: ESCS) = {
        actor2num += (escs.actorRef -> escs)
        num2actor += (escs.number -> escs)
    }
    def add(n: Int, ar: ActorRef, soAddr: InetSocketAddress) = +=(EchoSocketClientState(n,ar, soAddr))
    def -=(escs: ESCS) = {
        actor2num -=  escs.actorRef
        num2actor -= escs.number
    }
    def get(n: Int): Option[ESCS] = num2actor.get(n)
    def get(ar: ActorRef): Option[ESCS] = actor2num.get(ar)
    def delete(n: Int) = get(n) match {
        case Some(escs) => -=(escs)
        case None => throw new IllegalArgumentException()
    }
    def delete(ar: ActorRef) = get(ar) match {
        case Some(escs) => -=(escs)
        case None => throw new IllegalArgumentException()
    }
    def contains(n: Int) = num2actor.contains(n)
    def contains(ar: ActorRef) = actor2num.contains(ar)
    def mapToList[B](f: (ESCS) => B): List[B] = actor2num.toList.map(tp => f(tp._2))
}
object EchoSocketClientStates {
    type ESCSS = EchoSocketClientStates
}

class MainActor(addrPool: InetSocketAddressPool, dstSoAddr: InetSocketAddress, echoIntarval: Int) extends Actor {

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries=(-1), withinTimeRange=(1 second), loggingEnabled=true) {
        case _: ConnectException => Restart
        case _: Exception => Stop
        case t => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    import EchoSocketClientState.ESCS
    import EchoSocketClientStates.ESCSS

    private val escss = new ESCSS

    def startEchoSocketClientActor(n: Int): Unit = escss.get(n) match {
        case Some(_) => log_error(s"${echoName(n)} has been running.")
        case None => addrPool.get match {
            case Some(lsa) => escss.add(n, EchoSocketClientActor.start(n,lsa.getAddress,dstSoAddr,echoIntarval), lsa)
            case None => log_error("All IP Address has been used.")
        }
    } 
    def stopEchoSocketClientActor(n: Int): Unit = escss.get(n) match {
        case Some(escs) => {
            context.stop(escs.actorRef)
            escss -= escs
        }
        case None => log_error(s"${echoName(n)} dose not exist.")
    }

    def printClients() {
        log_info(f"%n" + escss.mapToList(escs => s"${echoName(escs.number)}:${escs.localSoAddr}").mkString(f"%n"))
    }
   
    def updateLocalSocketAddress(n: Int, soAddr: InetSocketAddress): Unit = escss.get(n) match {
        case Some(escs) => escss += EchoSocketClientState(soAddr, escs)
        case None => log_error(s"${echoName(n)} not exists.")
    }

    private val actorStates = HZActorStates()

    override def preStart() {
        actorStates += MyInputActor.start(System.in)
    }

    def receive = {
        case InputCommand.ICMD_Start(n) => startEchoSocketClientActor(n)
        case InputCommand.ICMD_Stop(n) => stopEchoSocketClientActor(n)
        case InputCommand.ICMD_StartRange(s,e) => (s to e) foreach(n => startEchoSocketClientActor(n))
        case InputCommand.ICMD_StopRange(s,e) => (s to e).foreach(n => stopEchoSocketClientActor(n))
        case InputCommand.ICMD_PrintEchoSocketClients => printClients
        case MainActor.IPAddressRelease(addr) => addrPool.release(new InetSocketAddress(addr,0))
        case EchoSocketClientActor.LocalSocketAddressUpdate(n, soAddr) => updateLocalSocketAddress(n, soAddr)
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
    case class IPAddressRelease(addr: InetAddress)

    def start(addrPool: InetSocketAddressPool, dstSoAddr: InetSocketAddress, echoIntarval: Int)(implicit system: ActorRefFactory): ActorRef = {
        system.actorOf(Props(new MainActor(addrPool,dstSoAddr,echoIntarval)))
    }
}

object EchoClient {
    implicit val logger = getLogger(this.getClass.getName)

    val config = ConfigFactory.load()

    def getConfigOrNone(key: String) = if(config.hasPath(key)) Some(config.getString(key)) else None

    def createInetSocketAddressPool(): Either[Throwable,InetSocketAddressPool] = catching(classOf[Exception]) either {
        val interfaceNameOpt = getConfigOrNone("echo_client.socket_address_pool.interface_name")
        val localAddressRangeOpt = getConfigOrNone("echo_client.socket_address_pool.local_address_range")
        val exceptIPAddressesOpt = getConfigOrNone("echo_client.socket_address_pool.except_ip_addresses")
        if(config.getBoolean("echo_client.socket_address_pool.use_address_recurse"))
            new SingleInetSocketAddressPool(localAddressRangeOpt, interfaceNameOpt, exceptIPAddressesOpt)
        else
            new InetSocketAddressPool(localAddressRangeOpt, interfaceNameOpt, exceptIPAddressesOpt)
    }

    def printUsage() = {
        val usage =
            """|Usage:
               |EchoClinet [-h] [dstSoAddr]
               |  -h        : ptirnt this usage.
               |  dstSoAddr : destination socket address (IP:TCP-Port)
               |""".stripMargin
        log_info(usage)
    }

    def parseArgument(args: Array[String], tp: Option[InetSocketAddress]): Option[Option[InetSocketAddress]] = {
        args match {
            case Array() => Some(tp)
            case Array(h, t @ _*) => {
                h match {
                    case "-h" => None
                    case arg if(t.isEmpty) => {
                        arg.split(':') match {
                            case Array(ip,portStr) => {
                                catching(classOf[NumberFormatException]) either {
                                    Integer.parseInt(portStr)
                                } match {
                                    case Right(port) => parseArgument(t.toArray, Some(new InetSocketAddress(ip,port)))
                                    case Left(th) => log_error(s"worng port number format in socket address : $arg") ; None
                                }
                            }
                            case a => log_error(s"worng socket address format : $arg") ; None
                        }
                    }
                    case arg => log_error(s"unknown argsument : $arg") ; None
                }
            }
        }
    }

    def main(args: Array[String]) {

        val dstSoAddr = parseArgument(args, None) match {
            case Some(opt) => opt match {
                case Some(dsa) => dsa
                case None => new InetSocketAddress(config.getString("echo_client.destination_address"), config.getInt("echo_client.destination_port"))
            }
            case None => sys.exit(1)
        }
        log_debug(s"dstSoAddr=$dstSoAddr")

        val inetSocketAddressPool = createInetSocketAddressPool() match {
            case Right(pool) => pool
            case Left(th) => {
                log_error(th.getMessage)
                sys.exit(1)
            }
        }
        log_debug(s"inetSocketAddressPool:$inetSocketAddressPool")

        implicit val system = ActorSystem("EchoClient")
        MainActor.start(inetSocketAddressPool, dstSoAddr, config.getInt("echo_client.echo_intarval"))(system)
        Await.result(system.whenTerminated, Duration.Inf)
    }
}

