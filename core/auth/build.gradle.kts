plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
}

dependencies {
    api(project(":core:common"))
    // 用 BouncyCastle 的轻量级 API（AES-GCM、X25519、Ed25519），不注册 JCA Provider；
    // 安卓 4.x 的平台实现不一定提供这些原语。
    implementation(libs.bouncycastle.prov)
}
