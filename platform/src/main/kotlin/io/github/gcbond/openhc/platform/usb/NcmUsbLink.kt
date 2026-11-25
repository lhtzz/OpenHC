package io.github.gcbond.openhc.platform.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import io.github.gcbond.openhc.core.common.MalformedMessageException
import io.github.gcbond.openhc.platform.net.NtbOutLayout
import io.github.gcbond.openhc.platform.net.NtbPort
import java.io.Closeable
import java.io.IOException

/**
 * 抢下手机的 USB 网卡接口，用两个批量端点收发 NTB。
 *
 * 车机内核里如果有 CDC NCM 驱动，手机一切过来它就会先绑上；强占（`claimInterface(…, true)`）会把它踢开，
 * 普通应用就能做到（实测）。关闭后内核驱动可以重新绑上。
 *
 * 关闭前要先停掉所有在用 [receive] / [send] 的线程（见 [io.github.gcbond.openhc.platform.net.NcmDataPath.stop]）。
 */
internal class NcmUsbLink private constructor(
    private val connection: UsbDeviceConnection,
    private val controlInterface: UsbInterface,
    private val dataInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    override val receiveSize: Int,
    override val outLayout: NtbOutLayout,
    override val maxOutBlockLength: Int,
    override val zlpPacketSize: Int,
    /** 手机报的 NTB 参数；取不到时为 null（用了默认值）。 */
    val parameters: NtbParameters?,
) : NtbPort, Closeable {

    private var closed = false

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        val startedAt = SystemClock.elapsedRealtime()
        val n = try {
            connection.bulkTransfer(bulkIn, buffer, minOf(receiveSize, buffer.size), timeoutMs)
        } catch (e: RuntimeException) {
            return -1
        }
        if (n >= 0) return n
        // bulkTransfer 超时和出错都返回 -1，只能看耗时区分：设备拔掉、权限没了时会立刻返回。
        return if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs / 2) 0 else -1
    }

    override fun send(buffer: ByteArray, length: Int, timeoutMs: Int): Boolean = try {
        connection.bulkTransfer(bulkOut, buffer, length, timeoutMs) == length
    } catch (e: RuntimeException) {
        false
    }

    /** 释放接口并关闭连接。可重复调用。 */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            connection.releaseInterface(dataInterface)
            connection.releaseInterface(controlInterface)
        } catch (e: RuntimeException) {
            // 设备已拔出时释放会失败，照样关连接。
        }
        connection.close()
    }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val NOTIFICATION_TIMEOUT_MS = 300
        private const val DEFAULT_ZLP_PACKET_SIZE = 512

        /** 切换手机和车机内核的备用设置，不能只向手机发 SET_INTERFACE 控制传输。 */
        private fun setInterfaceCompat(connection: UsbDeviceConnection, iface: UsbInterface, alternate: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (!connection.setInterface(iface)) throw IOException("数据口切到备用 $alternate 失败")
            } else {
                LegacyUsbInterface.setAlternate(connection.fileDescriptor, iface.id, alternate)
            }
        }

        /** 旧框架枚举了各备用接口及端点，却没有保留备用号；从 USB 描述符中按端点匹配。 */
        private fun alternateSettingOf(
            iface: UsbInterface,
            description: UsbInterfaceDescription,
            legacyDescriptors: ByteArray?,
        ): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            iface.alternateSetting
        } else {
            legacyDescriptors?.let { LegacyUsbDescriptors.alternateSetting(it, description) }
                ?: throw IOException("无法从 USB 描述符确认接口 ${iface.id}（${iface.endpointCount} 个端点）的备用设置号")
        }

        /**
         * 打开切成 USB 网卡的手机：强占控制口和数据口 → 读 NTB 参数 → 数据口切到备用 0（NCM 功能复位）→
         * 需要时压低手机的 NTB 上限 → 切到带端点的备用设置 → 读掉一条连接通知。
         * 任何一步失败都关掉连接并抛 [IOException]，消息说明是哪一步。
         *
         * @param log 记录过程的回调（不含设备标识）。
         */
        fun open(usbManager: UsbManager, device: UsbDevice, sdkInt: Int, log: (String) -> Unit): NcmUsbLink {
            val selection = NcmInterfaces.select(UsbDescriber.describe(device))
                ?: throw IOException("没找到 NCM 控制口或带批量端点的数据口")
            val control = device.getInterface(selection.control.index)
            val dataActive = device.getInterface(selection.dataActive.index)
            val idleDescription = selection.dataIdle
            val bulkIn = dataActive.getEndpoint(selection.bulkIn.index)
            val bulkOut = dataActive.getEndpoint(selection.bulkOut.index)
            val notification = selection.notification?.let { control.getEndpoint(it.index) }

            val connection = try {
                usbManager.openDevice(device)
            } catch (e: RuntimeException) {
                null
            } ?: throw IOException("打开设备失败（没有授权或已拔出）")
            try {
                if (!connection.claimInterface(control, true) || !connection.claimInterface(dataActive, true)) {
                    throw IOException("抢占 NCM 接口失败")
                }
                val legacyDescriptors = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    connection.rawDescriptors ?: throw IOException("读不到 USB 网卡描述符")
                } else {
                    null
                }
                val activeAlternate = alternateSettingOf(dataActive, selection.dataActive, legacyDescriptors)
                log("USB 网卡数据接口 ${dataActive.id}：带端点的备用设置 $activeAlternate（${dataActive.endpointCount} 个端点）")
                val parameters = readNtbParameters(connection, control.id, log)
                // 加载失败不是可忽略的空闲档切换失败，避免同一错误连续报两遍。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) LegacyUsbInterface.requireLoaded()
                if (idleDescription != null) {
                    val dataIdle = device.getInterface(idleDescription.index)
                    try {
                        val idleAlternate = alternateSettingOf(dataIdle, idleDescription, legacyDescriptors)
                        setInterfaceCompat(connection, dataIdle, idleAlternate)
                    } catch (e: IOException) {
                        log("数据口切到空闲设置失败，继续：${e.message}")
                    }
                }
                val plan = CdcNcm.receivePlan(parameters?.ntbInMaxSize, sdkInt)
                plan.setInputSize?.let { size ->
                    // 规范要求数据口在备用 0 时才能改 NTB 输入上限。
                    val payload = CdcNcm.encodeNtbInputSize(size)
                    val n = connection.controlTransfer(
                        CdcNcm.REQUEST_TYPE_OUT, CdcNcm.SET_NTB_INPUT_SIZE, 0, control.id,
                        payload, payload.size, CONTROL_TIMEOUT_MS,
                    )
                    log(
                        if (n == payload.size) "已把手机的 NTB 上限压到 $size 字节（系统单次传输上限）"
                        else "压低手机 NTB 上限失败（返回 $n），超过 $size 字节的块会被截断丢弃",
                    )
                }
                setInterfaceCompat(connection, dataActive, activeAlternate)
                log("USB 网卡数据接口 ${dataActive.id} 已切至备用 $activeAlternate，可开始收发")
                notification?.let {
                    // 手机切好后会在通知端点上报连接状态与速率；读掉一条，与实测通过的做法一致。读不到也不要紧。
                    connection.bulkTransfer(it, ByteArray(16), 16, NOTIFICATION_TIMEOUT_MS)
                }
                val layout = parameters
                    ?.takeIf { NtbOutLayout.isValid(it.outDivisor, it.outAlignment) }
                    ?.let { NtbOutLayout.of(it.outDivisor, it.outPayloadRemainder, it.outAlignment) }
                    ?: NtbOutLayout.DEFAULT
                val maxOut = parameters?.ntbOutMaxSize
                    ?.coerceIn(CdcNcm.MIN_NTB_SIZE.toLong(), 0xFFFFL)
                    ?.toInt()
                    ?: CdcNcm.MIN_NTB_SIZE
                val zlp = bulkOut.maxPacketSize.takeIf { it > 0 } ?: DEFAULT_ZLP_PACKET_SIZE
                log(
                    "USB 网卡就绪：每次接收 ${plan.receiveSize} 字节，出向上限 $maxOut 字节，$layout，" +
                        "端点包长 IN ${bulkIn.maxPacketSize} / OUT ${bulkOut.maxPacketSize}",
                )
                return NcmUsbLink(
                    connection, control, dataActive, bulkIn, bulkOut,
                    plan.receiveSize, layout, maxOut, zlp, parameters,
                )
            } catch (e: IOException) {
                closeQuietly(connection, control, dataActive)
                throw e
            } catch (e: RuntimeException) {
                closeQuietly(connection, control, dataActive)
                throw IOException("打开 USB 网卡出错: ${e.javaClass.simpleName}", e)
            }
        }

        private fun readNtbParameters(connection: UsbDeviceConnection, controlId: Int, log: (String) -> Unit): NtbParameters? {
            val buffer = ByteArray(CdcNcm.NTB_PARAMETERS_LENGTH)
            val n = connection.controlTransfer(
                CdcNcm.REQUEST_TYPE_IN, CdcNcm.GET_NTB_PARAMETERS, 0, controlId,
                buffer, buffer.size, CONTROL_TIMEOUT_MS,
            )
            val parameters = try {
                NtbParameters.parse(buffer, maxOf(n, 0))
            } catch (e: MalformedMessageException) {
                log("读 NTB 参数失败（返回 $n），用默认值")
                return null
            }
            log(
                "NTB 参数：格式 0x${Integer.toHexString(parameters.formatsSupported)}，" +
                    "入向上限 ${parameters.ntbInMaxSize}，出向上限 ${parameters.ntbOutMaxSize}，" +
                    "出向对齐 ${parameters.outDivisor}/${parameters.outPayloadRemainder}/${parameters.outAlignment}",
            )
            if (!parameters.supportsNtb16) log("手机说不支持 NTB16，照常按 NTB16 收发")
            return parameters
        }

        private fun closeQuietly(connection: UsbDeviceConnection, vararg interfaces: UsbInterface) {
            try {
                for (iface in interfaces) connection.releaseInterface(iface)
            } catch (e: RuntimeException) {
                // 照样关连接。
            }
            connection.close()
        }
    }
}
