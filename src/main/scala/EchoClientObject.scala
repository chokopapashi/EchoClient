
import java.net.InetSocketAddress

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.Exception._

import akka.actor.ActorSystem

import com.typesafe.config.Config

import org.hirosezouen.hzutil._
import HZLog._
import org.hirosezouen.hznet.InetSocketAddressPool

trait EchoClientObject {
    implicit val logger = getLogger(this.getClass.getName)

    val config: Config
    val clientName: String
    val mao: MainActorObject

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
            s"""|Usage:
                |$clientName [-h] [dstSoAddr]
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

    def start(args: Array[String]): Int = {
        val dstSoAddr = parseArgument(args, None) match {
            case Some(opt) => opt match {
                case Some(dsa) => dsa
                case None => new InetSocketAddress(config.getString("echo_client.destination_address"), config.getInt("echo_client.destination_port"))
            }
            case None => return 1
        }
        log_debug(s"dstSoAddr=$dstSoAddr")

        val inetSocketAddressPool = createInetSocketAddressPool() match {
            case Right(pool) => pool
            case Left(th) => {
                log_error(th.getMessage)
                return 2
            }
        }
        log_debug(s"inetSocketAddressPool:$inetSocketAddressPool")

        implicit val system = ActorSystem(clientName)
        mao.start(inetSocketAddressPool, dstSoAddr, config.getInt("echo_client.echo_intarval"), clientName)(system)
        Await.result(system.whenTerminated, Duration.Inf)
        0
    }
}

