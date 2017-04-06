
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.nio.channels.SelectableChannel
import java.nio.channels.SocketChannel

import scala.collection.mutable
import scala.collection.JavaConverters._

import EchoClientName._

trait SocketChannelIO {
    val ch: SelectableChannel
    val selector: Selector
    val echoName: EchoClientName

    val sendBuffer = ByteBuffer.wrap(new Array[Byte](64))
    val recvBuffer = ByteBuffer.wrap(new Array[Byte](64))

    def sendConcrete(buffer: ByteBuffer)

    def send(bytes: Array[Byte]) {
        sendBuffer.clear
        sendBuffer.put(bytes)
        sendBuffer.flip

        while(sendBuffer.position < sendBuffer.limit)
            sendConcrete(sendBuffer)
    }

    def recvConcrete(arrayBuffer: mutable.ArrayBuffer[Byte])

    def recv(): Either[String,Array[Byte]] = {
        val arrayBuffer = mutable.ArrayBuffer.empty[Byte]
        val ret = selector.select(5000)
        if(0 < ret) {
            val keySet = selector.selectedKeys().asScala
            for(key <- keySet) {
                keySet.remove(key)
                if(key.isReadable) {
                    recvConcrete(arrayBuffer)
                } else
                    throw new IllegalStateException(key.toString)
            }
            Right(arrayBuffer.toArray)
        } else
            Left(s"$echoName:$ret = selector.select()")
    }
}

class TcpSocketChannelIO(val ch: SocketChannel, val selector: Selector, val echoName: EchoClientName) extends SocketChannelIO {
    def sendConcrete(buffer: ByteBuffer) {
        ch.write(sendBuffer)
    }

    def recvConcrete(arrayBuffer: mutable.ArrayBuffer[Byte]) {
        recvBuffer.clear
//        val ret2 = key.channel.asInstanceOf[SocketChannel].read(recvBuffer)
        val ret2 = ch.read(recvBuffer)
        if(0 < ret2) {
            recvBuffer.flip
            recvConcrete(arrayBuffer ++= recvBuffer.array.take(recvBuffer.limit))
        }
    }
}

class UDPSocketChannelIO(val ch: DatagramChannel, val selector: Selector, val echoName: EchoClientName) extends SocketChannelIO {
    def sendConcrete(buffer: ByteBuffer) {
        ch.write(sendBuffer)
    }

    def recvConcrete(arrayBuffer: mutable.ArrayBuffer[Byte]) {
        recvBuffer.clear
//        val ret2 = key.channel.asInstanceOf[DatagramChannel].read(recvBuffer)
        val ret2 = ch.read(recvBuffer)
        if(0 < ret2) {
            recvBuffer.flip
            arrayBuffer ++= recvBuffer.array.take(recvBuffer.limit)
        }
    }
}

object EchoSocketUtil {
}

