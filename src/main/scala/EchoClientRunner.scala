import org.hirosezouen.hzutil._
import HZLog._

object EchoClientRunner {
    implicit val logger = getLogger(this.getClass.getName)
  
    def printUsage() = {
        val usage = """|Usage:
                       |EchoClientRunner mode
                       |  mode : 1 = EchoTcpClient
                       |  mode : 2 = EchoUdpClient
                       |  mode : 3 = EchoTestTcpServer
                       |  mode : 4 = EchoTestUdpServer
                       |""".stripMargin
        log_info(usage)
    }

    def main(args: Array[String]) {
        val ret = args match {
            case Array() => {
                log_error("arguments required.")
                printUsage
                1
            }
            case Array(mode, rest @ _*) => mode match {
                case "1" => EchoTcpClient.start(rest.toArray)
                case "2" => EchoUdpClient.start(rest.toArray)
                case "3" => EchoTestTcpServer.start(rest.toArray)
//                case "4" => EchoTestUdpServer.start(rest.toArray)
                case _   => {
                    log_error(s"invalid mode : $mode")
                    printUsage
                    2
                }
            }
        }
        sys.exit(ret)
    }
}


