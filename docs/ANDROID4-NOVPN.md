# API 17 有线免 VPN 实验

基线：`v40210`。目标是在不创建 Android VPN 的情况下，通过应用内 lwIP 使用已验证的 USB NCM 链路。保留系统 USB 权限、HiCar 配对及有线身份，不更改系统路由或网络设置。不把现有的 VPN 确认当成可绕过的步骤。

## 第一步：独立 IPv4 数据面

- 固定 lwIP `STABLE-2_2_1_RELEASE`、提交 `77dcd25a72509eb83f72b033d219b1d40cd8eb95`，只带所需核心和公开头文件。采用 `NO_SYS=1` 的 raw API，不引入系统网络线程、TUN 或 IPv6 代码路径。
- `LwipIpv4Tunnel` 实现 `NcmDataPath` 使用的 `PacketTunnel` 契约：现有 USB/NCM/以太网/ARP 层留在原处，向内只交付本 USB 网段发给车机的 IPv4 单播包。使用有界出包队列，旧会话句柄不能关闭新会话。
- 原生库为 Android API 16–17 的 32 位加载器保留 4 KiB ELF 布局。桌面包级测试 `python3 tools/test_lwip_ipv4.py` 使用合成 IPv4 帧验证 ICMP、UDP、TCP SYN-ACK、队列和关闭后重开，不访问外部网络。

**本阶段不会改变实际投屏的 VPN 路径。** 下一步要将 `ReceiverSession` 的监听 / TCP / UDP 动作接到 lwIP，并把接口生命周期和有线启动流程串起来；只有实验通路实际完成后才提供开关。不把未接通的后端暴露成「免 VPN」选项。

实车验收：冷启动插线无 VPN 框、能连接并正常投屏、拔插继续免码回连。原有 TUN 路径作为回退保留。
