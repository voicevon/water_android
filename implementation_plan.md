手机休眠或后台运行一段时间后导致 MQTT 断开、无法接收告警消息，这是 Android 系统（尤其是国产手机 ROM）极其普遍的后台保活与网络休眠机制导致的。

在**“不计较耗电”**的前提下，我们来进行一次深入的技术头脑风暴和原因分析。

一、 核心原因诊断：为什么休眠后 MQTT 会断开？
Android Doze Mode（打盹模式）与电池优化
从 Android 6.0 开始，手机屏幕关闭且静止一段时间后，系统会自动进入 Doze Mode。
在 Doze 模式下，系统会挂起 CPU WakeLock、切断/延迟后台网络访问，把原本实时发送的网络请求统一延后集中处理。
TCP 心跳超时与 NAT 映射失效
MQTT 基于 TCP 长连接。由于 Doze 模式把心跳定时器（Ping）延后了，导致 MQTT 客户端无法按时发心跳包，MQTT Broker 便认为客户端已离线并主动断开。
此外，移动网络（4G/5G/Wi-Fi）的防火墙/路由器都有 NAT 映射存活期，如果长时间没有心跳交互，运营商或路由器会切断此 TCP 链路。
国产 ROM 厂商的“暴徒式”后台清理策略
小米 (MIUI/HyperOS)、华为 (HarmonyOS)、OPPO (ColorOS)、vivo (OriginOS) 等，在屏幕熄灭一段时间后，除了切断网络，还会强制清理没有前台服务护航的后台 App 进程。
二、 微信 / 即时通讯软件是怎么做到“后台/休眠实时收消息”的？
微信等大型 IM 软件之所以能够做到零延迟收消息，主要依赖 “系统级通道” + “系统级白名单” + “全套保活组合拳”：

厂商系统级推送通道（最核心秘密）
在国外是 FCM (Firebase Cloud Messaging)；在国内则是各大手机厂商的 系统级 Push 通道（小米推送、华为推送、OPPO/vivo 推送）。
关键机制：手机系统本身会与厂商服务器保持唯一一条系统级 TCP 长连接（由系统进程维护，不受 Doze 模式影响）。
微信服务器发消息时，如果发现 App 没在线，会直接走厂商 Push 接口发给手机系统。手机系统收到后，直接弹窗响铃唤醒 App。哪怕微信进程被彻底杀掉，也能收到消息！
厂商系统白名单（ROM 特权）
手机厂商在出厂 ROM 时，就把微信、QQ、钉钉等应用加入到了“后台电池优化白名单”和“自启动白名单”中，不予以杀进程或切断网络。
前台服务 + 唤醒锁组合拳
微信后台长期挂载 Foreground Service（前台服务），保持较高的进程优先级（oom_adj 值低，不容易被杀）。
使用 PowerManager.WakeLock 保持 CPU 不休眠，使用 WifiLock 保持 Wi-Fi 高性能连通。
三、 针对我们 App 的头脑风暴解决方案（在允许耗电的前提下）
如果我们希望我们的 Android App 也能像微信/安防监控软件一样做到休眠期实时接收 MQTT 告警，可以考虑以下几个层面的方案：

方案 A：应用层硬核保活（纯 MQTT 架构，改动最小）
如果不改变现有的 MQTT 架构，仅在 Android 端做保活增强：

前台服务（Foreground Service）+ 常驻通知栏
开启带有常驻通知（例如“水质监控服务运行中…”）的前台服务，提升 App 进程优先级，防止被系统杀掉。
申请“忽略电池优化”白名单（Ignore Battery Optimizations）
在 App 启动时引导用户授权 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。授权后，系统 Doze 模式不再限制该 App 的后台网络访问和 CPU WakeLock。
CPU 唤醒锁与 Wi-Fi 锁
使用 PowerManager.PARTIAL_WAKE_LOCK 保证 MQTT 收到数据包时 CPU 处于工作状态。
使用 WifiManager.WIFI_MODE_FULL_HIGH_PERF 阻止系统在休眠时将 Wi-Fi 降频或断开。
精确心跳定时器 (AlarmManager.setExactAndAllowWhileIdle)
修改 MQTT 心跳实现，使用 Android 原生 AlarmManager 配合 setExactAndAllowWhileIdle() 发送 Ping。即使在 Doze 模式下，这个 API 也能精准唤醒 CPU 发送 MQTT 心跳包。
引导用户开启 ROM 厂商权限
App 内提供引导图文，提示用户去手机设置中开启：“允许自启动”、“后台无限制/允许高耗电”、“后台任务锁卡片”。
方案 B：云端/服务器中转 + 系统级 Push 通道（终极稳定性，微信同款）
如果追求 100% 告警到达率，单靠 MQTT 长连接在部分国产手机上仍有概率被系统杀掉。最佳的现代安防/告警架构是：

架构设计：
ESP32-CAM 发生告警 -> 发布 MQTT 到云端 Broker/服务器。
云端服务器收到告警 MQTT 后 -> 调用 第三方统一推送平台（如极光推送 JPush / 个提） 或厂商 Push API。
手机系统接收到系统级推送 -> 弹出系统通知/播放报警音。
用户点击通知 -> 打开 App 并拉取详细数据/视频流。
优势：
即使 App 被完全关闭、手机深层休眠，告警也绝不丢失。
极其省电，日常不需要手机端 App 一直挂在后台连 MQTT。
四、 总结与建议讨论
如果不引入第三方云端推送：方案 A（前台服务 + 忽略电池优化白名单 + 精确 Alarm 心跳 + 厂商后台自启动引导）是最直接且不用改后端/云端的解法。
如果要求 100% 绝对可靠（即使 App 被用户手动划掉杀后台）：方案 B（云端 MQTT 触发系统 Push）是行业标杆解法。

-->用A.  并且MQTT retain=true.

非常赞同您的思路！走 方案 A（App 端保活优化） + MQTT Retained 消息机制 是投入产出比最高、架构最轻量的方案。

retained = true（保留消息）配合客户端主动覆盖消除，巧妙地解决了“手机休眠重连期间错失关键告警”的硬伤。

按照您的要求，暂不修改代码，我们把 方案 A 的各个优化项 + Retained 消除机制 逐个拆解并梳理如下：

一、 MQTT Retained 保留消息与消除机制设计
ESP32 端（发送告警）

动作：检测到异动/报警时，ESP32 发布 MQTT 告警消息，设置 retained = true（QoS = 1）。
效果：MQTT Broker（服务器）会强制保存这条最新的告警消息。即便 Android 手机此时正处于休眠/断网状态，只要手机重新连上 MQTT，Broker 会立刻把这条告警补发给手机，确保消息绝不丢失。
Android 端（接收与消除/覆盖）

收到告警：Android 收到告警后触发响铃/通知。
自动/手动消除：手机端在确认接收（或用户处理告警）后，向同一个 Topic 发送一条 Payload 为空或 {"alarm": false} 的消息，同样设置 retained = true。
效果：覆盖并抹除 Broker 上保存的告警消息，防止 App 以后每次打开都重复弹旧告警。
二、 Android 方案 A 的 5 个保活优化点逐个拆解
为了在手机熄屏休眠时尽可能维持 MQTT 长连接，方案 A 包含以下 5 个维度的优化：

1. 前台服务（Foreground Service）+ 常驻通知
目的：将应用进程优先级升至前台（提升 oom_adj），防止 Android 系统在后台内存不足时杀掉 App 进程。
要点：
创建常驻通知栏（如：“水质/设备监控守护中”）。
适配 Android 13/14+ 的前台服务类型（foregroundServiceType）。
2. 电池优化白名单（Ignore Battery Optimizations）
目的：突破 Android 系统 Doze 模式（打盹模式）对后台网络访问和 CPU 唤醒的强制封锁。
要点：
App 启动时检查 PowerManager.isIgnoringBatteryOptimizations()。
若未开启，引导用户一键跳转系统设置允许 App“忽略电池优化”。
3. CPU 唤醒锁 (WakeLock) 与 Wi-Fi 高性能锁 (WifiLock)
目的：确保手机熄屏后 CPU 保持微弱运转、Wi-Fi 不自动降频或休眠断网。
要点：
在 MQTT 监听服务中持有 PowerManager.PARTIAL_WAKE_LOCK（仅保持 CPU，不亮屏）。
持有 WifiManager.WIFI_MODE_FULL_HIGH_PERF 保证无线网络稳定。
4. 基于 AlarmManager 的精确心跳（Doze 兼容心跳）
目的：传统的定时器在 Doze 模式下会被冻结，导致 MQTT 心跳包延迟发送而触发超时断开。
要点：
使用 Android 的 AlarmManager.setExactAndAllowWhileIdle() 机制来驱动 MQTT 心跳包（Ping）。
即使系统进入深层打盹，系统也会按时唤醒一个微小窗口允许 MQTT 发送心跳，维持 TCP 链路。
5. 国产 ROM 自启动与后台无限制引导（UI 引导）
目的：适配小米/华为/OPPO/vivo 等系统额外的“自启动”和“后台无限制”开关。
要点：
在 App 内提供“后台防护诊断”设置页，帮助用户快速跳转到手机系统的“自启动管理”和“后台高耗电允许”页面。


# Implementation Plan - Android App Keep-Alive & MQTT Retained Alarm Management

This plan details the technical approach to solve Android MQTT disconnection during sleep/background modes and prevent missing critical alarm messages without needing third-party cloud push services.

## User Review Required

> [!IMPORTANT]
> - **Battery Usage**: This implementation requests permission to ignore battery optimizations and holds a `PARTIAL_WAKE_LOCK` + `WifiLock` in a Foreground Service. This will slightly increase power consumption when the app is in the background, as requested.
> - **MQTT Retained Alarm Clear Protocol**:
>   - ESP32 publishes alarm with `retained = true`, QoS = 1.
>   - When Android receives and handles the alarm, Android will automatically (or via user interaction) publish a retained message with payload `""` or `{"alarm": false}` and `retained = true` to clear the retained state on the MQTT Broker.

## Proposed Changes

### Android Foreground Service & Keep-Alive Enhancement

#### [MODIFY] [AndroidManifest.xml](file:///d:/Software/antigravity/water_android/app/src/main/AndroidManifest.xml)
- Add permissions:
  - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - `WAKE_LOCK`
  - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` / `FOREGROUND_SERVICE_DATA_SYNC` (for Android 14+ compatibility).

#### [MODIFY] [MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt)
- **Foreground Service**: Ensure service runs as Foreground Service with persistent notification channel (`startForeground`).
- **Locks**: Acquire `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.WIFI_MODE_FULL_HIGH_PERF` lock while service is running.
- **Heartbeat / Doze Alarm**: Use `AlarmManager.setExactAndAllowWhileIdle()` to send periodic MQTT Ping requests when running in background/Doze mode.
- **Retained Message Handling & Clearance**:
  - When an alarm topic message is received, trigger notification/alarm ring.
  - Send an MQTT publish back to the alarm topic with `retained = true` and payload `""` / `{"alarm": false}` to overwrite and clear the retained message on the broker.

#### [MODIFY] [MainActivity.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/MainActivity.kt)
- Check `PowerManager.isIgnoringBatteryOptimizations()`. If false, display a dialog prompting the user to grant "Ignore Battery Optimizations".
- Add guidance UI for OEM ROM settings (Autostart / Background activity unrestricted).

## Verification Plan

### Manual Verification
1. **Retained Message Clear Verification**:
   - Trigger alarm on ESP32 or MQTT client with `retained = true`.
   - Connect Android MQTT service; verify notification is received.
   - Verify Android publishes a blank retained message back to the topic to clear it from Broker.
2. **Background / Sleep Verification**:
   - Turn off screen and let phone enter Doze mode / sleep for > 15 minutes.
   - Send an alarm via ESP32 / MQTT broker.
   - Verify Android immediately receives the alarm or catches up via Retained message upon reconnecting.
