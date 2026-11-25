package io.github.gcbond.openhc.connection

/**
 * 仅用于同一个应用进程内比较有线配对和回连。身份与公钥只放在内存，不输出原值，也不写信任库。
 */
internal class WiredIdentitySnapshot(
    private val bluetoothSource: BluetoothSource,
    private val deviceId: String,
    publicKey: ByteArray?,
) {
    private val publicKey = publicKey?.copyOf()
    val hasStoredCarKey: Boolean get() = publicKey != null

    fun diagnostic(previousPairing: WiredIdentitySnapshot?): String {
        val source = when (bluetoothSource) {
            BluetoothSource.SYSTEM_PROPERTY -> "系统读取"
            BluetoothSource.MANUAL -> "手动设置"
            BluetoothSource.WIRED_LOCAL -> "应用内保存的有线专用地址"
            BluetoothSource.UNAVAILABLE -> "未取得（固定退化值）"
        }
        val prefix = "蓝牙地址来源=$source；有已保存的车机公钥=$hasStoredCarKey"
        if (previousPairing == null) return "$prefix；本进程尚无可比较的有线配对"
        val sameId = deviceId == previousPairing.deviceId
        val otherKey = previousPairing.publicKey
        val sameKey = if (publicKey == null || otherKey == null) "不可比较" else publicKey.contentEquals(otherKey).toString()
        return "$prefix；与本进程上次有线配对相比：车机ID相同=$sameId，车机公钥相同=$sameKey"
    }
}
