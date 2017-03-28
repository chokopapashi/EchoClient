
import java.net.InetSocketAddress
import scala.collection.immutable.Queue

import org.hirosezouen.hzutil._
import HZLog._

import org.hirosezouen.hznet.InetSocketAddressPool

class SingleInetSocketAddressPool(addrRangeStrOpt: Option[String], interfaceNameOpt: Option[String], exceptIPAddressesOpt: Option[String])
extends InetSocketAddressPool(addrRangeStrOpt, interfaceNameOpt, exceptIPAddressesOpt)
{
    override def get(): Option[InetSocketAddress] = {
        log_trace(s"SingleInetSocketAddressPool:get")
        if(0 < queue.size) {
            val socketAddress = queue.head
            log_trace(f"InetSocketAddressPool:get:queue.size=${queue.size}%d:$socketAddress%s")
            Some(socketAddress)
        } else {
            log_trace(f"InetSocketAddressPool:get:NG:queue.size=${queue.size}%d")
            None
        }
    }

    override def release(socketAddress: InetSocketAddress) {
        log_trace(s"SingleInetSocketAddressPool:release($socketAddress):Nothing to do")
    } 
}

