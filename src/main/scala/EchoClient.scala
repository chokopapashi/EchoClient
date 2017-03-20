import scala.concurrent.Await
import scala.concurrent.duration._
//import scala.language.postfixOps
import scala.util.control.Exception._

import akka.actor.ActorSystem

import com.typesafe.config.ConfigFactory

import org.hirosezouen.hzutil._
import HZLog._

import org.hirosezouen.hznet.InetSocketAddressPool

object EchoClient {
    implicit val logger = getLogger(this.getClass.getName)

    val config = ConfigFactory.load()

    def createInetSocketAddressPool(): Either[Throwable,InetSocketAddressPool] = {
        val interfaceNameOpt = if(config.hasPath("interface_name")) Some(config.getString("interface_name")) else None
        val localAddressRangeOpt = if(config.hasPath("local_address_range")) Some(config.getString("local_address_range")) else None
        val exceptIPAddressesOpt = if(config.hasPath("except_ip_addresses")) Some(config.getString("except_ip_addresses")) else None
        catching(classOf[Exception]) either {
            new InetSocketAddressPool(localAddressRangeOpt, interfaceNameOpt, exceptIPAddressesOpt)
        }
    }

    def main(args: Array[String]) {
        log_trace("EchoClient")

        val inetSocketAddressPool = createInetSocketAddressPool() match {
            case Right(pool) => pool
            case Left(th) => {
                log_error(th.getMessage)
                sys.exit(1)
            }
        }
        log_info(s"inetSocketAddressPool:$inetSocketAddressPool")

        implicit val system = ActorSystem("EchoClient")
        MainActor.start(
            inetSocketAddressPool,
            config.getInt("destination_port"),
            config.getInt("echo_intarval"))(system)
        Await.result(system.whenTerminated, Duration.Inf)
    }
}

