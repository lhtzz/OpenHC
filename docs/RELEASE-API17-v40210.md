# OpenHC API17 · v40210

- 基于 OpenHC 0.2.7 Android 4 分支；最低 Android 4.1（API 16），使用 NDK r23c 兼容旧车机。
- 保留有线拔插回连和应用内切页的画布处理。投屏中通过 HiCar「车机」或车机 HOME 退到系统桌面后，回前台会重新绑定画布并尝试重放完整的视频缓存。Android 4.2.2 用户已确认画面可以恢复。
- 顶部系统栏以窗口实测边界为准；Android 4.x 关闭全屏时优先按系统可用尺寸协商，避免多留黑边。
- 此版仍使用系统 VPN 建立有线虚拟网卡；Android 4.2.2 冷启动后的 VPN 确认与 USB 授权是两件事。

## 公开 APK 和实测 APK 的区别

GitHub 附件是 **不含连接身份的公开预览包**，可安装体验界面、检查系统兼容性，不能直接连手机。预览包名 `io.github.gcbond.openhc.api17public`，不会覆盖 `io.github.gcbond.openhc.api17test` 的本机实测包，不更换实测用户的签名或配对记录。不要卸载实测包来安装预览。

- 公开预览：`./gradlew :app:assembleRelease -Popenhc.api17Profile=public-preview`（不提供身份；可配置自己的签名）。
- 本机实测构建：同一命令改为 `-Popenhc.api17Profile=local-test`，按 README 通过 `OPENHC_IDENTITY_DIR` 提供自己的连接参数。要覆盖既有实测包，需使用与已安装版本相同的签名。Android 4.x 安装包须有 v1 签名。

公开源码不包括连接身份、钥库、口令或配对材料。源码对应 Git 标签 `v40210`，许可证为 GPL-3.0-only。
