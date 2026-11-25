package io.github.gcbond.openhc.connection

import io.github.gcbond.openhc.core.common.identity.HostIdentity
import io.github.gcbond.openhc.core.nearby.NearbySession
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** 车机蓝牙地址的来源顺序与格式整理。地址都是合成值。 */
class BluetoothAddressPolicyTest {

    private val property = "0A:1B:2C:3D:4E:5F"
    private val manual = "02:12:34:56:78:9A"

    /**
     * 无线也用**手填的优先**：从系统读地址本来就靠猜厂商写在哪个属性里，猜错会读到一个看起来合法、
     * 其实是别的设备的地址，二维码带着它手机永远连不上、车机这边还不报错。车主照着「关于本机」填的一定是真的。
     */
    @Test
    fun wirelessPrefersManualOverSystemProperty() {
        assertEquals(
            BluetoothChoice(manual, BluetoothSource.MANUAL),
            BluetoothAddressPolicy.chooseForWireless(property, manual),
            "两者都有时用手填的",
        )
        assertEquals(
            BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY),
            BluetoothAddressPolicy.chooseForWireless(property, null),
            "没手填才用系统读到的",
        )
        assertEquals(
            BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY),
            BluetoothAddressPolicy.chooseForWireless(property, "  "),
            "手填是空白等于没填",
        )
    }

    @Test
    fun locallyAdministeredBitIsDetected() {
        assertEquals(true, BluetoothAddressPolicy.isLocallyAdministered("02:12:34:56:78:9A"))
        assertEquals(true, BluetoothAddressPolicy.isLocallyAdministered("06:11:22:33:44:55"))
        assertEquals(true, BluetoothAddressPolicy.isLocallyAdministered("0A:1B:2C:3D:4E:5F"), "0x0A 的本地管理位为 1")
        assertEquals(false, BluetoothAddressPolicy.isLocallyAdministered("00:1B:2C:3D:4E:5F"))
        assertEquals(false, BluetoothAddressPolicy.isLocallyAdministered("A8:1B:2C:3D:4E:5F"))
    }

    @Test
    fun manualWins() {
        assertEquals(BluetoothChoice(manual, BluetoothSource.MANUAL), BluetoothAddressPolicy.choose(property, manual))
    }

    @Test
    fun systemPropertyIsUsedWhenManualIsMissingOrUnusable() {
        val expected = BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY)
        for (bad in listOf(null, "", "   ", "garbage", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) {
            assertEquals(expected, BluetoothAddressPolicy.choose(property, bad), "手填值：$bad")
        }
    }

    @Test
    fun manualIsUsedWhenThePropertyIsMissingOrUnusable() {
        val expected = BluetoothChoice(manual, BluetoothSource.MANUAL)
        for (bad in listOf(null, "", "   ", "garbage", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00")) {
            assertEquals(expected, BluetoothAddressPolicy.choose(bad, manual), "属性值：$bad")
        }
    }

    @Test
    fun unavailableWhenNeitherIsUsable() {
        assertEquals(BluetoothChoice(null, BluetoothSource.UNAVAILABLE), BluetoothAddressPolicy.choose(null, null))
        assertEquals(BluetoothChoice(null, BluetoothSource.UNAVAILABLE), BluetoothAddressPolicy.choose("", "12:34"))
    }

    @Test
    fun normalizesCommonSpellings() {
        assertEquals(property, BluetoothAddressPolicy.normalize("0a:1b:2c:3d:4e:5f"))
        assertEquals(property, BluetoothAddressPolicy.normalize("0A-1B-2C-3D-4E-5F"))
        assertEquals(property, BluetoothAddressPolicy.normalize("0a1b2c3d4e5f"))
        assertEquals(property, BluetoothAddressPolicy.normalize("  0A:1B:2C:3D:4E:5F\n"))
    }

    @Test
    fun rejectsMalformedAndPlaceholderAddresses() {
        for (bad in listOf(
            "0A:1B:2C:3D:4E", "0A:1B:2C:3D:4E:5G", "0A:1B-2C:3D:4E:5F", "0A1B2C3D4E5", "0A:1B:2C:3D:4E:5F:60",
            "00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "02:00:00:00:00:00",
        )) {
            assertNull(BluetoothAddressPolicy.normalize(bad), bad)
        }
    }

    @Test
    fun unavailableAddressDerivesTheSameDeviceIdAsThePlaceholder() {
        // 取不到地址时传 null：设备 ID 按退化规则算，与显式写 02:00:00:00:00:00 相同；换了地址就是另一台车。
        val host = HostIdentity("000000", "00", "合成品牌", "合成车机")
        assertEquals(NearbySession.deviceId(host, "02:00:00:00:00:00"), NearbySession.deviceId(host, null))
        assertNotEquals(NearbySession.deviceId(host, null), NearbySession.deviceId(host, property))
    }

    @Test
    fun wiredGeneratesOnlyWhenNeededAndThenKeepsTheSavedChoice() {
        var generated = 0
        val create = { generated++; manual }
        val fallback = BluetoothChoice(manual, BluetoothSource.WIRED_LOCAL)
        assertEquals(BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY),
            BluetoothAddressPolicy.chooseForWired(property, null, null, create))
        assertEquals(0, generated)
        assertEquals(fallback, BluetoothAddressPolicy.chooseForWired(null, null, null, create))
        assertEquals(fallback, BluetoothAddressPolicy.chooseForWired(property, null, manual, create))
        assertEquals(fallback, BluetoothAddressPolicy.chooseForWired(null, property, manual, create),
            "后来为无线填真实地址，不改变既有有线身份")
        assertEquals(1, generated)
    }

    @Test
    fun movingAManualAddressToWiredOnlyKeepsTheUsbIdentityNotTheWirelessOverride() {
        val host = HostIdentity("000000", "00", "合成品牌", "合成车机")
        val before = BluetoothAddressPolicy.chooseForWired(property, manual, null)
        val after = BluetoothAddressPolicy.chooseForWired(property, null, manual)
        assertEquals(NearbySession.deviceId(host, before.address), NearbySession.deviceId(host, after.address))
        assertEquals(BluetoothSource.WIRED_LOCAL, after.source)
        assertEquals(BluetoothChoice(property, BluetoothSource.SYSTEM_PROPERTY), BluetoothAddressPolicy.chooseForWireless(property, null))
        assertNull(BluetoothAddressPolicy.chooseForWireless(null, null).address)
    }

    @Test
    fun wiredLocalAddressIsUnicastAndNeverTheReservedPlaceholder() {
        val zero = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) = bytes.fill(0)
        }
        assertEquals("02:00:00:00:00:01", BluetoothAddressPolicy.newWiredLocalAddress(zero))
        assertEquals(BluetoothChoice(null, BluetoothSource.UNAVAILABLE),
            BluetoothAddressPolicy.chooseForWired(null, null, null) { null })
        assertEquals(BluetoothChoice(manual, BluetoothSource.MANUAL),
            BluetoothAddressPolicy.chooseForWired(property, manual, null))
    }

    /** 属性名清单：常用的几个都在，且第一个就是文档里写的那个。 */
    @Test
    fun propertyKeysCoverCommonVendors() {
        assertEquals(BluetoothAddressPolicy.SYSTEM_PROPERTY_KEY, BluetoothAddressPolicy.PROPERTY_KEYS.first())
        for (key in listOf("persist.sys.bt_addr", "persist.vendor.bt.bdaddr", "ro.bt.bdaddr")) {
            assertEquals(true, key in BluetoothAddressPolicy.PROPERTY_KEYS, key)
        }
        assertEquals(BluetoothAddressPolicy.PROPERTY_KEYS.size, BluetoothAddressPolicy.PROPERTY_KEYS.distinct().size, "不能有重复")
    }
}
