// SPDX-License-Identifier: GPL-3.0-only
#include "ipv4_tunnel.h"

#include <jni.h>

static int valid_slice(JNIEnv *env, jbyteArray bytes, jint offset, jint length) {
    return bytes && offset >= 0 && length > 0 && offset <= (*env)->GetArrayLength(env, bytes) - length;
}

JNIEXPORT jlong JNICALL
Java_io_github_gcbond_openhc_platform_net_LwipIpv4Native_nOpen(
    JNIEnv *env, jobject self, jbyteArray address, jint prefix) {
    (void)self;
    uint8_t ip[4];
    if (!address || (*env)->GetArrayLength(env, address) != 4 || prefix < 1 || prefix > 30) return 0;
    (*env)->GetByteArrayRegion(env, address, 0, 4, (jbyte *)ip);
    if ((*env)->ExceptionCheck(env)) return 0;
    return (jlong)openhc_lwip_ipv4_open(ip, (unsigned)prefix);
}

JNIEXPORT jboolean JNICALL
Java_io_github_gcbond_openhc_platform_net_LwipIpv4Native_nWrite(
    JNIEnv *env, jobject self, jlong token, jbyteArray bytes, jint offset, jint length) {
    (void)self;
    uint8_t data[OPENHC_LWIP_MTU];
    if (length > OPENHC_LWIP_MTU || !valid_slice(env, bytes, offset, length)) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, bytes, offset, length, (jbyte *)data);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return openhc_lwip_ipv4_input((uint64_t)token, data, (size_t)length) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_gcbond_openhc_platform_net_LwipIpv4Native_nRead(
    JNIEnv *env, jobject self, jlong token, jbyteArray bytes, jint offset, jint length) {
    (void)self;
    uint8_t data[OPENHC_LWIP_MTU];
    if (!valid_slice(env, bytes, offset, length)) return -1;
    size_t capacity = length < OPENHC_LWIP_MTU ? (size_t)length : OPENHC_LWIP_MTU;
    int n = openhc_lwip_ipv4_read((uint64_t)token, data, capacity);
    if (n > 0) (*env)->SetByteArrayRegion(env, bytes, offset, n, (jbyte *)data);
    return n;
}

JNIEXPORT void JNICALL
Java_io_github_gcbond_openhc_platform_net_LwipIpv4Native_nClose(
    JNIEnv *env, jobject self, jlong token) {
    (void)env;
    (void)self;
    openhc_lwip_ipv4_close((uint64_t)token);
}
