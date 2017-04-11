
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.channels.ServerSocketChannel
//import java.util.concurrent.Executors

import scala.collection.JavaConverters._
//import scala.concurrent.Future
//import scala.concurrent.ExecutionContext
import scala.util.control.Exception._

import org.hirosezouen.hzutil._
import HZLog._

trait EchoTestServer {
    implicit val logger = getLogger(this.getClass.getName)
//    implicit val executor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(1000))

    val serverName: String
    def openChannel(port: Int, selector: Selector): SelectableChannel

    var acceptHandler: Function2[SelectableChannel, Selector, Unit] = (ch, selector) => throw new IllegalStateException("Not implement.")
    var readableHandler: Function2[SelectableChannel, ByteBuffer, Unit] = (ch, buffer) => throw new IllegalStateException("Not implement.")

    def start(args: Array[String]): Int = {
        log_info(s"$serverName:start")

        var ch: SelectableChannel = null
        val selector = Selector.open
        val buffer = ByteBuffer.wrap(new Array[Byte](64))

        ultimately {
            selector.close
            if(ch != null) ch.close
        } {
            if(args.length != 2) {
                log_error("arguments required.")
                return 1;
            }
            if(args(0) != "-L") {
                log_error(s"wrong argument:${args(0)}")
                return 2
            }

            val port = Integer.parseInt(args(1))
            ch = openChannel(port, selector)
            while(true) {
                val ret = selector.select()
                if(0 < ret) {
                    val keySet = selector.selectedKeys().asScala
                    for(key <- keySet) {
                        keySet.remove(key)
                        if(key.isAcceptable) {
                            acceptHandler(key.channel, selector)
                        } else if(key.isReadable) {
                            readableHandler(key.channel, buffer)
                        } else
                            throw new IllegalStateException(key.toString)
                    }
                } else
                    log_debug(s"$ret = selector.select()")
            }
            0
        }
    }
}

object EchoTestTcpServer extends EchoTestServer {
    val serverName: String = "EchoTestTcpServer"

    def openChannel(port: Int, selector: Selector): SelectableChannel = {
        val ch = ServerSocketChannel.open
        ch.bind(new InetSocketAddress(port))
        ch.configureBlocking(false)
        ch.register(selector, SelectionKey.OP_ACCEPT)
        ch
    }

    acceptHandler = {
        (svch, selector) =>
        val soch = svch.asInstanceOf[ServerSocketChannel].accept
        log_debug(s"new connection:$soch")
        soch.configureBlocking(false)
        soch.register(selector, SelectionKey.OP_READ)
    }

    readableHandler = {
        (ch, buffer) =>
        val soch = ch.asInstanceOf[SocketChannel]
        def recvConcrete() {
            buffer.clear
            val ret2 = soch.read(buffer)
            if(0 < ret2) {
                buffer.flip
                log_debug(s"$ret2:${new String(buffer.array,0,buffer.limit)}")
                while(buffer.position < buffer.limit)
                    soch.write(buffer)
                recvConcrete()
            } else if(ret2 == 0) {
                ;   /* Nothing to do. */
            } else {
                log_error(s"read()==-1:stream arrive at end:$soch")
                soch.close
            }
        }
        recvConcrete()
    }
}

object EchoTestUdpServer extends EchoTestServer {
    val serverName: String = "EchoUdpTcpServer"
   
    def openChannel(port: Int, selector: Selector): SelectableChannel = {
        val ch = DatagramChannel.open
        ch.bind(new InetSocketAddress(port))
        ch.configureBlocking(false)
        ch.register(selector, SelectionKey.OP_READ)
        ch
    }

    readableHandler = {
        (ch, buffer) =>
        val dgch = ch.asInstanceOf[DatagramChannel]
        buffer.clear
        val peerAddr = dgch.receive(buffer)
        if(peerAddr != null) {
            buffer.flip
            log_debug(s"${new String(buffer.array,0,buffer.limit)}")
            while(buffer.position < buffer.limit)
                dgch.send(buffer, peerAddr)
        } else
            log_error(s"dgch.receive() == null:$dgch")
    }
}

