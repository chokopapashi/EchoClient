
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.Executors

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

import org.hirosezouen.hzutil._
import HZLog._

object EchoTestServer {
    implicit val logger = getLogger(this.getClass.getName)
    implicit val executor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1000))

    def start(args: Array[String]): Int = {
        log_debug("EchoTestServer:start")

        val port = Integer.parseInt(args(1))
        val ssoch = ServerSocketChannel.open.bind(new InetSocketAddress(InetAddress.getLocalHost,port))

        while(true) {
            log_info("Waiting new connection ...")
            val soch = ssoch.accept()
            log_info(s"new connection accepted:$soch")
            Future {
                log_trace(s"Future start:$soch")
                val buffer = ByteBuffer.wrap(new Array[Byte](64))
                var recv_flag = true
                while(recv_flag) {
                    buffer.clear
                    val ret = soch.read(buffer)
                    log_debug(s"ret=$ret:${new String(buffer.array,0,buffer.limit)}")
                    if(0 < ret) {
                        buffer.flip
                        while(buffer.position < buffer.limit)
                            soch.write(buffer)
                    } else if(ret == 0) {
                        ;   /* Nothing to do. */
                    } else {
                        log_error(s"soch.read()==-1:stream arrive at end:$soch")
                        recv_flag = false
                     }
                }
            }
        }
        0
    }
}

