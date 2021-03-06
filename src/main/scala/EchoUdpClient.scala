
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.collection.immutable.SortedMap
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
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

class EchoUDPClientMainActor(
    val addrPool: InetSocketAddressPool,
    val dstSoAddr: InetSocketAddress,
    val echoIntarval: Int,
    val clientName: String)
extends MainActor
{
    def scao = EchoUDPSocketClientActor
}

object EchoUDPClientMainActor extends MainActorObject {
    def start(addrPool: InetSocketAddressPool,
              dstSoAddr: InetSocketAddress,
              echoIntarval: Int,
              clientName: String)
             (implicit system: ActorRefFactory): ActorRef
    = {
        system.actorOf(Props(new EchoUDPClientMainActor(addrPool,dstSoAddr,echoIntarval,clientName)))
    }
}

object EchoUdpClient extends EchoClientObject {
    val config = ConfigFactory.load()
    val clientName = "EchoUdpClient"
    val mao = EchoUDPClientMainActor
}

