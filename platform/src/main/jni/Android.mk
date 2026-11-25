LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := openhc_legacy_usb
LOCAL_SRC_FILES := legacy_usb.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
# This small C shim only uses libc; it has no constructors or C++ runtime.
LOCAL_SYSTEM_SHARED_LIBRARIES := libc
LOCAL_LDFLAGS := -nostdlib -Wl,--as-needed
ifneq ($(filter armeabi-v7a x86,$(TARGET_ARCH_ABI)),)
    # The API 16/17 loader reserves the 4 KiB-rounded PT_LOAD span, then mprotects
    # PT_GNU_RELRO. With a 16 KiB common page its padded RELRO can exceed that span.
    LOCAL_LDFLAGS += -Wl,--hash-style=sysv -Wl,-z,max-page-size=4096 -Wl,-z,common-page-size=4096
else
    LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
endif
include $(BUILD_SHARED_LIBRARY)
