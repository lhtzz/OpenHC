#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

JNIEXPORT jint JNICALL
Java_io_github_gcbond_openhc_platform_usb_LegacyUsbInterface_setInterfaceNative(
    JNIEnv *env, jclass klass, jint fd, jint interface_id, jint alternate) {
    (void)env;
    (void)klass;
    if (fd < 0) return EBADF;
    if (interface_id < 0 || interface_id > 255 || alternate < 0 || alternate > 255) return EINVAL;

    /* usbfs updates the host kernel's bulk endpoint state as well as the device. */
    struct usbdevfs_setinterface setting = {
        .interface = (unsigned int)interface_id,
        .altsetting = (unsigned int)alternate,
    };
    int result;
    do {
        result = ioctl(fd, USBDEVFS_SETINTERFACE, &setting);
    } while (result < 0 && errno == EINTR);
    return result < 0 ? errno : 0;
}
