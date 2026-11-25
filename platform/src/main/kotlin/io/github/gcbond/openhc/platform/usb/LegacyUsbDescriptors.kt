package io.github.gcbond.openhc.platform.usb

/** API 16～20 的 UsbInterface 没有备用设置号；从 USB 标准描述符恢复实际的 bAlternateSetting。 */
internal object LegacyUsbDescriptors {
    private const val DEVICE = 1
    private const val CONFIGURATION = 2
    private const val INTERFACE = 4
    private const val ENDPOINT = 5

    /** 用接口身份和端点列表匹配，出现损坏或多个不同的候选时不猜。 */
    fun alternateSetting(raw: ByteArray, target: UsbInterfaceDescription): Int? {
        fun byte(index: Int): Int = raw[index].toInt() and 0xff

        val expectedEndpoints = target.endpoints.map { endpointKey(it.address, it.type) }.sorted()
        val candidates = mutableSetOf<Int>()
        var current: Interface? = null

        fun finishInterface() {
            val matched = current ?: return
            if (matched.endpointCount == expectedEndpoints.size && matched.endpoints.sorted() == expectedEndpoints) {
                candidates.add(matched.alternate)
            }
            current = null
        }

        var offset = 0
        while (offset < raw.size) {
            if (raw.size - offset < 2) return null
            val length = byte(offset)
            if (length < 2 || length > raw.size - offset) return null
            when (byte(offset + 1)) {
                DEVICE, CONFIGURATION -> finishInterface()
                INTERFACE -> {
                    finishInterface()
                    if (length < 9) return null
                    if (byte(offset + 2) == target.id && byte(offset + 5) == target.interfaceClass &&
                        byte(offset + 6) == target.interfaceSubclass && byte(offset + 7) == target.interfaceProtocol
                    ) {
                        current = Interface(byte(offset + 3), byte(offset + 4))
                    }
                }
                ENDPOINT -> {
                    if (length < 7) return null
                    current?.endpoints?.add(endpointKey(byte(offset + 2), byte(offset + 3) and 3))
                }
            }
            offset += length
        }
        finishInterface()
        return candidates.singleOrNull()
    }

    private fun endpointKey(address: Int, type: Int): Int = (address shl 2) or type

    private class Interface(val alternate: Int, val endpointCount: Int) {
        val endpoints = mutableListOf<Int>()
    }
}
