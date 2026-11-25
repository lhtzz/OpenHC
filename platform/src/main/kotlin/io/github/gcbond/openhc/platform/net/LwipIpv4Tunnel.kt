package io.github.gcbond.openhc.platform.net

import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address

/**
 * 免 VPN 的 IPv4 数据面原型。在 USB 层与 [NcmDataPath] 使用同一 [PacketTunnel] 契约，ARP 仍由原来的
 * [EthernetEndpoint] 处理。lwIP 的 raw API 必须串行：USB 收线程、USB 发线程及以后会话适配器都只在 gate 内调用。
 * 这一步没有接入有线连接入口，不改变现有 VPN 会话的行为。
 */
internal class LwipIpv4Tunnel private constructor(private var token: Long) : PacketTunnel, Closeable {
    private var stopped = false

    override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean = synchronized(gate) {
        if (stopped || token == 0L || !validSlice(buffer, offset, length)) return@synchronized false
        LwipIpv4Native.nWrite(token, buffer, offset, length).also { if (it) gate.notifyAll() }
    }

    override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int = synchronized(gate) {
        if (stopped || token == 0L || !validSlice(buffer, offset, maxLength)) return@synchronized -1
        val ready = LwipIpv4Native.nRead(token, buffer, offset, maxLength)
        if (ready != 0) return@synchronized ready
        // 有新包或关闭会主动叫醒；空闲也定期驱动 lwIP 的定时器，不用忙等或系统网络线程。
        try {
            gate.wait(POLL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return@synchronized -1
        }
        if (stopped || token == 0L) -1 else LwipIpv4Native.nRead(token, buffer, offset, maxLength)
    }

    override fun wakeup() {
        synchronized(gate) {
            stopped = true
            gate.notifyAll()
        }
    }

    override fun close() {
        synchronized(gate) {
            stopped = true
            if (token != 0L) LwipIpv4Native.nClose(token)
            token = 0L
            gate.notifyAll()
        }
    }

    companion object {
        private const val POLL_MS = 25L
        private val gate = java.lang.Object()

        @Throws(IOException::class)
        fun open(address: Inet4Address, prefixLength: Int): LwipIpv4Tunnel = synchronized(gate) {
            require(prefixLength in 1..30) { "lwIP requires a unicast IPv4 subnet (1..30)" }
            val unavailable = LwipIpv4Native.loadFailure
            if (unavailable != null) throw IOException("无法加载 lwIP IPv4 数据面", unavailable)
            val token = LwipIpv4Native.nOpen(address.address, prefixLength)
            if (token == 0L) throw IOException("lwIP 无法开启 IPv4 接口（地址无效或已有会话）")
            LwipIpv4Tunnel(token)
        }

        private fun validSlice(buffer: ByteArray, offset: Int, length: Int): Boolean =
            offset >= 0 && length > 0 && offset <= buffer.size - length
    }
}

private object LwipIpv4Native {
    val loadFailure: Throwable? by lazy {
        try {
            System.loadLibrary("openhc_lwip_ipv4")
            null
        } catch (e: LinkageError) {
            e
        } catch (e: SecurityException) {
            e
        }
    }

    external fun nOpen(address: ByteArray, prefixLength: Int): Long
    external fun nWrite(token: Long, buffer: ByteArray, offset: Int, length: Int): Boolean
    external fun nRead(token: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nClose(token: Long)
}
