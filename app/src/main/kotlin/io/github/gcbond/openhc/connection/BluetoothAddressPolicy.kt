package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.common.identity.BluetoothAddress
import java.security.SecureRandom

/** 车机蓝牙地址取自哪里。 */
enum class BluetoothSource {
    /** 车机系统属性。 */
    SYSTEM_PROPERTY,

    /** 用户在设置里手填。 */
    MANUAL,

    /** 只作为 OpenHC 的有线身份，绝不用于无线广播、RFCOMM 或蓝牙配对。 */
    WIRED_LOCAL,

    /** 没有可用地址：无线不能启动；有线若无法保存专用地址，回退原来的占位规则。 */
    UNAVAILABLE,
}

/** 选定的车机蓝牙地址。[address] 是大写、冒号分隔的文本；取不到时为 null。 */
data class BluetoothChoice(val address: String?, val source: BluetoothSource)

/**
 * 车机蓝牙地址怎么定。纯逻辑。
 *
 * 安卓 6.0 起普通应用读不到本机蓝牙地址（系统只给 `02:00:00:00:00:00`）。有的车机系统把它放在一个普通应用可读的
 * 系统属性里（部分车机把它写在 `persist.sys.bt_addr`）。用户在设置里手填了有效地址时优先用手填的
 * （车主可以借此覆盖系统的值）。无线没有手填时用系统地址；有线还可以用保存在本应用里的专用地址。
 *
 * 地址参与 carInfo、控制通道握手、认证身份和设备信息里的本机标识：**地址变了，手机会把车机当成另一台车**，
 * 之前的配对记录对不上，要重新输入连接码。
 */
object BluetoothAddressPolicy {
    /** 最常用的那个属性名，保留给文档和旧代码引用。 */
    const val SYSTEM_PROPERTY_KEY = "persist.sys.bt_addr"

    /**
     * 车机可能用来存本机蓝牙地址的系统属性名，按可靠性从高到低排。
     * 各家方案商写法不同（高通、联发科、瑞芯微、比亚迪、长城等都有各自的），全部试一遍。
     */
    val PROPERTY_KEYS = listOf(
        "persist.sys.bt_addr",
        "persist.vendor.bt.bdaddr",
        "persist.bluetooth.bdaddr",
        "ro.bt.bdaddr_path_addr",
        "persist.service.bdroid.bdaddr",
        "ro.boot.btmacaddr",
        "net.hostname.bt",
        "persist.sys.bt.mac",
        "ro.bt.bdaddr",
        "bluetooth.address",
        "persist.bt.mac",
        "ro.bt.mac",
        "ro.bluetooth.address",
        "ro.vendor.bt.bdaddr",
        "persist.radio.btmacaddr",
        "persist.vendor.qcom.bluetooth.bdaddr",
        "bt_mac",
        "ro.product.bt.address",
    )

    /** Settings.Global / Settings.Secure 里可能存着本机蓝牙地址的键。 */
    val SETTINGS_KEYS = listOf(
        "bluetooth_address",
        "bluetooth_addr",
        "bt_address",
        "bt_mac_address",
    )

    /** 全 0、全 F：属性没初始化或读错了。 */
    private val INVALID = setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", BluetoothAddress.UNAVAILABLE)

    fun choose(systemProperty: String?, manual: String?): BluetoothChoice {
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        normalize(systemProperty)?.let { return BluetoothChoice(it, BluetoothSource.SYSTEM_PROPERTY) }
        return BluetoothChoice(null, BluetoothSource.UNAVAILABLE)
    }

    /**
     * 已保存的有线身份优先，之后给无线填写真实地址也不会改变有线配对。还没有有线专用身份时，
     * 沿用现有手填地址（包括 v286 的配置），再尝试系统读取和新建。
     *
     * [createLocal] 只能交回已经持久化的值；设置页预览时不生成。
     */
    internal fun chooseForWired(
        systemProperty: String?,
        manual: String?,
        savedLocal: String?,
        createLocal: () -> String? = { null },
    ): BluetoothChoice {
        normalize(savedLocal)?.let { return BluetoothChoice(it, BluetoothSource.WIRED_LOCAL) }
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        val system = choose(systemProperty, null)
        if (system.address != null) return system
        return normalize(createLocal())?.let { BluetoothChoice(it, BluetoothSource.WIRED_LOCAL) } ?: system
    }

    /** 每套安装独立生成的本地管理单播值，仅用于有线身份；不是实际蓝牙硬件地址。 */
    internal fun newWiredLocalAddress(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BluetoothAddress.SIZE).also(random::nextBytes)
        bytes[0] = 0x02
        if (bytes.drop(1).all { it == 0.toByte() }) bytes[bytes.lastIndex] = 1
        return BluetoothAddress.format(bytes)
    }

    /**
     * 无线连接用的地址：**手填的优先**，没填才用系统里读到的。
     *
     * 为什么手填优先：从系统读地址本来就没有可靠办法（安卓 6 起公开接口一律给占位值），只能碰运气去猜厂商写在哪个
     * 属性里。猜错了会读到一个**看起来合法但其实是别的设备**的地址，二维码里就带着错地址，手机拿去连车机蓝牙永远
     * 连不上，车机这边只停在「等扫码」、不报任何错——这比读不到更难查。
     *
     * 所以让车主手填的地址压过系统读到的值：车主照着「关于本机」填的那一份一定是真的。系统读到的值只在没填时兜底。
     */
    fun chooseForWireless(systemProperty: String?, manual: String?): BluetoothChoice {
        normalize(manual)?.let { return BluetoothChoice(it, BluetoothSource.MANUAL) }
        normalize(systemProperty)?.let { return BluetoothChoice(it, BluetoothSource.SYSTEM_PROPERTY) }
        return BluetoothChoice(null, BluetoothSource.UNAVAILABLE)
    }

    /** 第一个字节的「本地管理」位为 1：这类地址是软件编出来的，不会是蓝牙芯片的出厂地址。 */
    fun isLocallyAdministered(address: String): Boolean =
        BluetoothAddress.parse(address)[0].toInt() and 0x02 != 0

    /**
     * 整理成 `AA:BB:CC:DD:EE:FF`：接受冒号或 `-` 分隔、大小写，也接受不带分隔的 12 位十六进制；
     * 前后空白去掉。格式不对，或是全 0、全 F、`02:00:00:00:00:00` 这类占位值，返回 null。
     */
    fun normalize(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        val withSeparators = if (trimmed.length == 12 && trimmed.all { it.isAsciiHex() }) {
            trimmed.chunked(2).joinToString(":")
        } else {
            trimmed
        }
        val bytes = try {
            BluetoothAddress.parse(withSeparators)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val formatted = BluetoothAddress.format(bytes)
        return formatted.takeIf { it !in INVALID }
    }

    private fun Char.isAsciiHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
