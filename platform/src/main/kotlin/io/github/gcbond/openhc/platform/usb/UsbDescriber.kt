package io.github.gcbond.openhc.platform.usb

import android.hardware.usb.UsbDevice

/** 安卓 `UsbDevice` → [UsbDeviceDescription]。 */
object UsbDescriber {

    fun describe(device: UsbDevice): UsbDeviceDescription {
        val interfaces = (0 until device.interfaceCount).map { i ->
            val iface = device.getInterface(i)
            UsbInterfaceDescription(
                index = i,
                id = iface.id,
                // API 21 以下这里只填占位值；真正的备用号在 openDevice 之后从原始描述符匹配。
                alternateSetting = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    iface.alternateSetting
                } else {
                    0
                },
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                interfaceProtocol = iface.interfaceProtocol,
                endpoints = (0 until iface.endpointCount).map { e ->
                    val endpoint = iface.getEndpoint(e)
                    UsbEndpointDescription(
                        index = e,
                        address = endpoint.address,
                        type = endpoint.type,
                        maxPacketSize = endpoint.maxPacketSize,
                    )
                },
            )
        }
        return UsbDeviceDescription(device.vendorId, device.productId, device.deviceClass, interfaces)
    }

    /** 这台设备从有线投屏的角度看处于哪种状态，给界面显示用。 */
    fun phoneMode(device: UsbDevice): PhoneUsbMode =
        if (!HuaweiPhone.isHuaweiVendor(device.vendorId)) PhoneUsbMode.NOT_A_PHONE else HuaweiPhone.classify(describe(device))

    /** 日志用的简短描述：厂商号、产品号、接口类别。不含序列号等设备标识。 */
    fun summary(device: UsbDevice): String {
        val d = describe(device)
        val interfaces = d.interfaces.joinToString(" ") { "${it.interfaceClass}/${it.interfaceSubclass}" }
        return "%04x:%04x 类 %d 接口 [%s]".format(java.util.Locale.ROOT, d.vendorId, d.productId, d.deviceClass, interfaces)
    }
}
