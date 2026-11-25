package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.common.identity.DeviceId
import io.github.gcbond.openhc.core.common.identity.HostIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WiredIdentitySnapshotTest {
    private val host = HostIdentity(modelId = "ABCDEF", subModelId = "01", brand = "example", deviceName = "example car")
    private val key = ByteArray(32) { -1 }

    @Test
    fun noBluetoothOnBothAttemptsDoesNotChangeTheCarIdentity() {
        val id = DeviceId.hex(host, null)
        val paired = WiredIdentitySnapshot(BluetoothSource.UNAVAILABLE, id, key)
        val reconnect = WiredIdentitySnapshot(BluetoothSource.UNAVAILABLE, DeviceId.hex(host, null), key.copyOf())
        val diagnostic = reconnect.diagnostic(paired)
        assertTrue(diagnostic.contains("蓝牙地址来源=未取得"), diagnostic)
        assertTrue(diagnostic.contains("车机ID相同=true，车机公钥相同=true"), diagnostic)
        assertFalse(diagnostic.contains(id) || diagnostic.contains(key.contentToString()))
    }

    @Test
    fun aReadAddressAndAChangedKeyAreReportedIndependently() {
        val paired = WiredIdentitySnapshot(BluetoothSource.UNAVAILABLE, DeviceId.hex(host, null), key)
        val id = DeviceId.hex(host, "02:AB:CD:EF:12:34")
        val sameKey = WiredIdentitySnapshot(BluetoothSource.SYSTEM_PROPERTY, id, key)
        val changedKey = WiredIdentitySnapshot(BluetoothSource.SYSTEM_PROPERTY, id, ByteArray(32))
        assertTrue(sameKey.diagnostic(paired).contains("车机ID相同=false，车机公钥相同=true"))
        assertTrue(changedKey.diagnostic(sameKey).contains("车机ID相同=true，车机公钥相同=false"))
    }

    @Test
    fun noReferenceOrNoSavedKeyIsNotReportedAsAMatch() {
        val id = DeviceId.hex(host, null)
        val current = WiredIdentitySnapshot(BluetoothSource.UNAVAILABLE, id, null)
        val paired = WiredIdentitySnapshot(BluetoothSource.UNAVAILABLE, id, key)
        assertFalse(current.hasStoredCarKey)
        assertTrue(current.diagnostic(null).contains("本进程尚无可比较的有线配对"))
        assertTrue(current.diagnostic(paired).contains("车机ID相同=true，车机公钥相同=不可比较"))
    }
}
