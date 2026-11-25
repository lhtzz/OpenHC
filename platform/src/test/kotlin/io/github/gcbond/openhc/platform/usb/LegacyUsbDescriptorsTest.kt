package io.github.gcbond.openhc.platform.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyUsbDescriptorsTest {
    private val idle = UsbInterfaceDescription(1, 4, 0, 10, 0, 0, emptyList())
    private val active = idle.copy(index = 2, endpoints = listOf(
        UsbEndpointDescription(0, 0x83, UsbEndpointDescription.TYPE_BULK, 512),
        UsbEndpointDescription(1, 0x04, UsbEndpointDescription.TYPE_BULK, 512),
    ))

    @Test
    fun findsIdleAndActiveSettingsEvenWhenTheOldFrameworkReportsZeroForBoth() {
        val raw = iface(id = 3, alternate = 0, count = 0, clazz = 2) +
            iface(id = 4, alternate = 0, count = 0) +
            iface(id = 4, alternate = 1, count = 2) + endpoint(0x83) + endpoint(0x04)
        assertEquals(0, LegacyUsbDescriptors.alternateSetting(raw, idle))
        assertEquals(1, LegacyUsbDescriptors.alternateSetting(raw, active))
    }

    @Test
    fun usesTheDescriptorNumberNotTheInterfaceIndexOrAGuessedOne() {
        val raw = iface(4, 1, 2) + endpoint(0x85) + endpoint(0x06) +
            iface(4, 6, 2) + endpoint(0x04) + endpoint(0x83)
        assertEquals(6, LegacyUsbDescriptors.alternateSetting(raw, active))
    }

    @Test
    fun rejectsWrongClassWrongEndpointTypeAndAmbiguousMatches() {
        val correct = iface(4, 1, 2) + endpoint(0x83) + endpoint(0x04)
        assertNull(LegacyUsbDescriptors.alternateSetting(iface(4, 1, 2, 0xff) + endpoint(0x83) + endpoint(0x04), active))
        assertNull(LegacyUsbDescriptors.alternateSetting(iface(4, 1, 2) + endpoint(0x83, 3) + endpoint(0x04), active))
        assertNull(LegacyUsbDescriptors.alternateSetting(correct + iface(4, 2, 2) + endpoint(0x83) + endpoint(0x04), active))
    }

    @Test
    fun rejectsTruncatedDataAndDoesNotReadEndpointsFromTheNextConfiguration() {
        val partial = iface(4, 1, 2) + endpoint(0x83)
        assertNull(LegacyUsbDescriptors.alternateSetting(partial + endpoint(0x04).dropLast(1).toByteArray(), active))
        assertNull(LegacyUsbDescriptors.alternateSetting(partial + byteArrayOf(0, 5), active))
        assertNull(LegacyUsbDescriptors.alternateSetting(partial + byteArrayOf(9, 2, 0, 0, 0, 0, 0, 0, 0) + endpoint(0x04), active))
    }

    private fun iface(id: Int, alternate: Int, count: Int, clazz: Int = 10) =
        byteArrayOf(9, 4, id.toByte(), alternate.toByte(), count.toByte(), clazz.toByte(), 0, 0, 0)

    private fun endpoint(address: Int, type: Int = 2) =
        byteArrayOf(7, 5, address.toByte(), type.toByte(), 0, 2, 0)
}
