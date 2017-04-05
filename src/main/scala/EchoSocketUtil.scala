
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

import scala.collection.mutable
import scala.collection.JavaConverters._

import EchoClientName._

case class SocketChannelIO(soch: SocketChannel, selector: Selector) {

    val sendBuffer = ByteBuffer.wrap(new Array[Byte](64))
    val recvBuffer = ByteBuffer.wrap(new Array[Byte](64))

    def send(bytes: Array[Byte])(implicit echoName: EchoClientName) {
        sendBuffer.clear
        sendBuffer.put(bytes)
        sendBuffer.flip

        while(sendBuffer.position < sendBuffer.limit)
            soch.write(sendBuffer)
    }

    def recv()(implicit echoName: EchoClientName): Either[String,Array[Byte]] = {
        val arrayBuffer = mutable.ArrayBuffer.empty[Byte]
        val ret = selector.select(5000)
        if(0 < ret) {
            val keySet = selector.selectedKeys().asScala
            for(key <- keySet) {
                keySet.remove(key)
                if(key.isReadable) {
                    def recvConcrete() {
                        recvBuffer.clear
                        val ret2 = key.channel.asInstanceOf[SocketChannel].read(recvBuffer)
                        if(0 < ret2) {
                            recvBuffer.flip
                            arrayBuffer ++= recvBuffer.array.take(recvBuffer.limit)
                            recvConcrete()
                        }
                    }
                    recvConcrete()
                } else
                    throw new IllegalStateException(key.toString)
            }
            Right(arrayBuffer.toArray)
        } else
            Left(s"$echoName:$ret = selector.select()")
    }
}

object EchoSocketUtil {
}

