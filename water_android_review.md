# water_android 项目代码审查报告

> 审查时间：2026-06-04 | 审查范围：全部 16 个源文件

---

## 一览：问题严重等级分布

| 等级 | 数量 | 说明 |
|------|------|------|
| 🔴 **严重 (Critical)** | 5 | 可导致崩溃、数据丢失或安全漏洞 |
| 🟠 **高危 (High)** | 6 | 功能性错误或生产环境可靠性隐患 |
| 🟡 **中危 (Medium)** | 7 | 逻辑 Bug、性能问题 |
| 🔵 **低危 (Low)** | 4 | 代码质量、可维护性问题 |

---

## 🔴 严重问题 (Critical)

### C-1：明文硬编码敏感凭据
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L186-L189) · [ConfigViewModel.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/viewmodel/ConfigViewModel.kt#L20-L29)

**问题**：MQTT Broker 地址 `voicevon.vicp.io`、用户名 `von`、密码 `von123456` 作为默认值直接写入代码，APK 逆向后即可提取，任何人可伪装成合法客户端接入系统，发送恶意控制指令。

```kotlin
// MqttService.kt:186 — 硬编码密码
val password = sharedPrefs.getString("password", "von123456") ?: "von123456"
```

**对策**：
- 将默认值改为空字符串，首次启动强制引导用户填写配置；
- 生产环境密码通过 Android Keystore 加密存储，不存入 SharedPreferences 明文；
- 代码提交前扫描 `.gitignore` 策略，防止 `local.properties` / `*.properties` 中的凭据泄露。

---

### C-2：`MqttService` 静态单例持有 `Context`，存在内存泄漏
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L60-L61)

**问题**：`companion object` 中 `@Volatile private var instance: MqttService?` 持有 Service 实例（间接持有 Context）。若 Service 被系统重启（`START_STICKY`），旧实例会残留一段时间，直接导致内存泄漏；多进程场景下亦会产生状态不一致。

```kotlin
companion object {
    @Volatile
    private var instance: MqttService? = null   // 危险
```

**对策**：使用 `LocalBroadcastManager` 或 Binder IPC 代替单例引用；或将共享 State 提升到 `Application` 级别的 ViewModel/Repository，彻底解耦 Service 与 UI 依赖。

---

### C-3：在 Compose Composable 中同步解码图片（ANR 风险）
**文件**：[MonitorScreen.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/screens/MonitorScreen.kt#L186) · [LogsScreen.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/screens/LogsScreen.kt#L153) · [LogsScreen.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/screens/LogsScreen.kt#L234)

**问题**：`BitmapFactory.decodeFile()` 是 IO 密集型阻塞调用，在 Composition 树中（主线程）直接执行，图片稍大（几百KB污水原图）即会导致帧丢失乃至 ANR。同时 `LogsScreen` 的 `LazyColumn` 中每一行 `LogItemCard` 都会触发一次同步解码，列表滚动时极易卡顿。

```kotlin
// MonitorScreen.kt:186 — 在 UI 线程直接 decodeFile
val bitmap = BitmapFactory.decodeFile(file.absolutePath)
```

**对策**：
- 使用 [Coil](https://coil-kt.github.io/coil/) 或 Glide 异步图片库，一行代码 `AsyncImage(model = file)` 即可替换；
- 若坚持手动，使用 `produceState<Bitmap?>` + `withContext(Dispatchers.IO)` 异步加载。

---

### C-4：`AndroidManifest.xml` 应用主题填写错误（构建时 Bug）
**文件**：[AndroidManifest.xml](file:///d:/Software/antigravity/water_android/app/src/main/AndroidManifest.xml#L17)

**问题**：`android:theme="@style/Theme."` — 主题名称末尾缺少实际名称，这是一个硬性编译/运行错误，安装后 Activity 无法正常启动或发生资源加载崩溃。

```xml
<!-- 错误：主题名不完整 -->
android:theme="@style/Theme.">
```

**对策**：补全主题名称，例如 `@style/Theme.WaterVon` 或 `@style/Theme.Material3.DynamicColors.DayNight`，并确保 `res/values/themes.xml` 中对应定义存在。

---

### C-5：`RECEIVE_BOOT_COMPLETED` 权限声明但无 `BroadcastReceiver` 实现
**文件**：[AndroidManifest.xml](file:///d:/Software/antigravity/water_android/app/src/main/AndroidManifest.xml#L8)

**问题**：Manifest 声明了 `RECEIVE_BOOT_COMPLETED` 权限，但代码中无任何 `BootReceiver` 实现，导致：
1. 设备重启后 MQTT 服务不会自动拉起（功能缺失）；
2. 冗余权限声明可能影响应用市场审核。

**对策**：若需开机自启，添加对应 `BroadcastReceiver`；若不需要，删除该权限声明。

---

## 🟠 高危问题 (High)

### H-1：重连逻辑可能无限创建 `MqttClient` 实例，造成资源泄漏
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L183-L242)

**问题**：`connectMqtt()` 每次调用都直接 `new MqttClient(...)`，但先前的 client 未显式 `close()`（仅在 `onDestroy` 中关闭）。网络不稳定时指数退避会多次调用 `connectMqtt()`，同时存在多个未关闭的 client 实例持有 TCP Socket 和线程资源。

**对策**：
```kotlin
private fun connectMqtt() {
    // 先关闭旧实例
    try { mqttClient?.close() } catch (_: Exception) {}
    mqttClient = null
    // 再创建新实例
    mqttClient = MqttClient(...)
}
```

---

### H-2：`LogManager` 在主线程被 `@Synchronized` 阻塞，可能导致 ANR
**文件**：[LogManager.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/data/LogManager.kt#L39) · [LogsViewModel.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/viewmodel/LogsViewModel.kt#L58-L68)

**问题**：`writeLog()` 和 `readLogs()` 均加了 `@Synchronized`，意味着二者会互相阻塞。`LogsViewModel.loadLogs()` 在 `viewModelScope.launch {}` 中调用（默认 Main 调度器），若 IO 耗时（日志文件大）会阻塞主线程。

**对策**：
- `loadLogs()` 改为 `viewModelScope.launch(Dispatchers.IO) { ... }`；
- `writeLog()` 已在 `serviceScope(Dispatchers.IO)` 中调用，问题不大，但 `@Synchronized` 可替换为 `Mutex` 以实现真正的协程友好并发控制。

---

### H-3：图片 Payload 无大小校验，大图可直接 OOM
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L293) · [LogManager.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/data/LogManager.kt#L110-L112)

**问题**：`pi_water/photo` 主题接收到原始二进制 payload 后，直接 `fos.write(imageBytes)` 无任何大小限制，在树莓派上传高分辨率图片或误发大文件时，Android 端将直接 OOM。

**对策**：
```kotlin
topic == "pi_water/photo" -> {
    if (payloadBytes.size > 5 * 1024 * 1024) { // 5MB 上限
        addConsoleLog("图片过大，已忽略: ${payloadBytes.size} bytes")
        return
    }
    // ...
}
```

---

### H-4：`saveAndConnect()` 重启服务但不停止旧连接，造成双重连接
**文件**：[ConfigViewModel.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/viewmodel/ConfigViewModel.kt#L63-L82)

**问题**：用户修改配置后点击"应用并重启连接"，代码仅调用 `startForegroundService()`。但由于 `onStartCommand` 检查了 `isServiceRunning` 标志，如果 Service 已运行，`connectMqtt()` 不会重新执行——新配置实际未生效，UI 显示"已连接"但连接的是旧服务器。

**对策**：
```kotlin
fun saveAndConnect() {
    // 先停止服务（断开旧连接）
    context.stopService(serviceIntent)
    // 再启动（新配置生效）
    context.startForegroundService(serviceIntent)
}
```
或在 Service 中监听带配置变更的 Intent Action 并重新连接。

---

### H-5：警报通知使用 `System.currentTimeMillis().toInt()` 作为 ID，存在溢出截断
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L372)

**问题**：`manager.notify(System.currentTimeMillis().toInt(), ...)` — `Long` 强转 `Int` 会溢出截断，造成不同时间的通知复用同一 ID 互相覆盖，用户将遗漏重要警报。

**对策**：使用原子递增计数器或固定区间 ID：
```kotlin
private val notificationCounter = AtomicInteger(2000)
manager.notify(notificationCounter.getAndIncrement(), alarmNotification)
```

---

### H-6：`ParametersViewModel.syncChannelParameters()` 在主线程同步调用 MQTT 发布
**文件**：[ParametersViewModel.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/viewmodel/ParametersViewModel.kt#L99-L100)

**问题**：`MqttService.publish()` 方法最终调用 Paho 的同步 `mqttClient.publish()`（阻塞 IO），而 `syncChannelParameters` 在 UI 事件回调中被直接调用，没有切换到后台协程，可能阻塞主线程。

**对策**：将 `syncChannelParameters` 改为 `suspend fun` 并在 `viewModelScope.launch(Dispatchers.IO)` 中执行。

---

## 🟡 中危问题 (Medium)

### M-1：MonitorScreen 中 Broker 地址硬编码在 UI 中，与实际配置不同步
**文件**：[MonitorScreen.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/screens/MonitorScreen.kt#L70)

**问题**：状态栏显示 `"Broker: voicevon.vicp.io:1883"` 为硬编码字符串，用户修改配置后 UI 显示仍为旧地址，产生误导。

**对策**：从 `MonitorViewModel` 暴露实际 Broker 地址状态流，或从 `SharedPreferences` 读取后展示。

---

### M-2：日志级别判断关键词存在明显笔误
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L316-L319)

**问题**：WARN 级别检测关键词为 `"失踪"` 和 `"丟"` — `"失踪"` 应为 `"失败"` 或类似词，`"丟"` 是繁体字/错别字（简体应为 `"丢"`），造成对应错误事件无法被正确识别为 WARN 级别。

```kotlin
// 当前（有误）：
actionMessage.contains("失踪") // 应为"失败"?
actionMessage.contains("丟")   // 繁体，应为"丢"
```

**对策**：与树莓派端协商统一日志格式，或改用结构化 JSON payload 携带 `level` 字段，彻底避免字符串关键词误判。

---

### M-3：`LogManager.cleanOldLogs()` 从未被调用
**文件**：[LogManager.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/data/LogManager.kt#L135)

**问题**：`cleanOldLogs()` 方法定义完整，但在整个项目中没有任何地方调用它，日志文件将无限积累，长期运行的设备外部存储会逐渐耗尽。

**对策**：在 `MqttService.onCreate()` 中使用 `WorkManager` 或直接调度一次清理：
```kotlin
serviceScope.launch { logManager.cleanOldLogs(30) }
```

---

### M-4：`LogManager` 使用 `getExternalFilesDir()` 且 `baseDir` 可能为 null
**文件**：[LogManager.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/data/LogManager.kt#L22-L29)

**问题**：`baseDir` 声明为可空 `File?`，但 `logsDir = File(baseDir, "logs")` 直接传入可空值，若外部存储不可用（如设备加密锁屏未解锁、外部存储被移除），将产生 NPE 或静默失败（日志写入到 `/null/logs/`）。

**对策**：
```kotlin
private val baseDir: File = context.getExternalFilesDir(null)
    ?: context.filesDir  // 降级到内部存储
```

---

### M-5：`ParametersViewModel` 修改滑块值但不自动保存，重建 ViewModel 后状态丢失
**文件**：[ParametersViewModel.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/viewmodel/ParametersViewModel.kt#L40-L47)

**问题**：`updateDuration1()` 等方法只更新内存 Flow，不写入 SharedPreferences。用户调整参数但未点击"同步远端"按钮的情况下，横竖屏切换或 Activity 重建后 ViewModel 重建，所有未同步的调整将丢失。

**对策**：在 `update*` 方法中同步写入 SharedPreferences，或使用 `SavedStateHandle` 保存 UI 状态。

---

### M-6：`addConsoleLog()` 在多协程并发下存在竞态条件
**文件**：[MqttService.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/service/MqttService.kt#L119-L128)

**问题**：
```kotlin
val currentLogs = mqttLogs.value.toMutableList()  // 读
currentLogs.add(0, ...)                             // 改
mqttLogs.value = currentLogs                        // 写
```
这是典型的非原子"读-改-写"，在多条 MQTT 消息并发到达时，可能出现日志行互相覆盖或丢失。

**对策**：使用 `MutableSharedFlow` + 单一协程消费者，或改用 `StateFlow.update {}` 的原子操作（需 Kotlin 1.6+）：
```kotlin
mqttLogs.update { current ->
    (listOf("[$timestamp] $message") + current).take(100)
}
```

---

### M-7：`LogsScreen` 中 `DatePickerDialog` 在 Compose 中每次重组都重新创建
**文件**：[LogsScreen.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/screens/LogsScreen.kt#L55-L69)

**问题**：`DatePickerDialog(...)` 在 Composable 函数体顶层创建（非 `remember` 包裹），每次 Recomposition 都创建新的对话框对象，造成内存浪费，且 Material3 已提供 `DatePicker` 纯 Compose 实现。

**对策**：
```kotlin
val datePickerDialog = remember(selectedDate) {
    DatePickerDialog(context, { ... }, ...)
}
```
或迁移到 Material3 的 `DatePickerDialog` Composable。

---

## 🔵 低危问题 (Low)

### L-1：`isMinifyEnabled = false` 在 Release 包中关闭代码混淆
**文件**：[build.gradle.kts](file:///d:/Software/antigravity/water_android/app/build.gradle.kts#L19-L22)

**问题**：生产构建未启用 ProGuard/R8 混淆，APK 可被轻易反编译，包含的服务器地址、业务逻辑等完全暴露。

**对策**：启用 `isMinifyEnabled = true` 并配置适当的 ProGuard 规则（保留 Paho MQTT 等必要类）。

---

### L-2：`SettingsScreen.kt` 中私有扩展函数覆盖了标准库函数签名
**文件**：[SettingsScreen.kt](file:///d:/Software/antigravity/water_android/app/src/main/java/com/water/von/ui/screens/SettingsScreen.kt#L122-L124)

**问题**：
```kotlin
private fun CoroutineScope.launch(block: suspend () -> Unit) {
    this.launch(block = { block() })  // 包装自身，实为递归调用！
}
```
这个扩展函数与 `kotlinx.coroutines.launch` 签名冲突，且内部实现是调用自身，导致无限递归栈溢出（实测会抛 `StackOverflowError`）。

**对策**：直接删除该私有扩展函数，使用标准 `scope.launch { ... }` 即可。

---

### L-3：`targetSdk` 仍为 34，Google Play 2025年要求最低 35
**文件**：[build.gradle.kts](file:///d:/Software/antigravity/water_android/app/build.gradle.kts#L13)

**问题**：Google Play 自 2025 年起要求新应用及更新的 `targetSdk >= 35`，当前 `targetSdk = 34` 后续提交 Play 将被拒绝。

**对策**：升级 `targetSdk = 35`，并同步检查 Android 15 行为变更兼容性（前台服务、通知权限等）。

---

### L-4：无任何单元测试覆盖核心业务逻辑
**问题**：`LogManager`、`LogEntry` 的序列化/反序列化、`MqttService` 消息路由逻辑均无测试，`build.gradle.kts` 中虽依赖了 `junit` 和 `kotlinx-coroutines-test`，但 `src/test/` 目录为空。

**对策**：优先为 `LogEntry.fromJsonString()` / `toJsonString()`、日志分级关键词判断编写单元测试。

---

## 综合风险评估

```
安全性     ████████░░  高风险（明文凭据 + 无混淆）
稳定性     ███████░░░  中高风险（ANR/OOM/竞态）
功能完整性 ██████░░░░  中风险（开机自启缺失/重连逻辑有误）
可维护性   █████░░░░░  一般（无测试 + 单例耦合）
```

---

## 优先修复建议（按 ROI 排序）

| 优先级 | 问题 | 预估工作量 |
|--------|------|-----------|
| 1 | **C-4** 修复 AndroidManifest 主题名（否则无法启动） | 5 分钟 |
| 2 | **L-2** 删除递归扩展函数（否则参数同步必崩溃） | 5 分钟 |
| 3 | **H-1** 重连前先关闭旧 MqttClient | 30 分钟 |
| 4 | **C-3** 用 Coil 替换同步 BitmapFactory | 1 小时 |
| 5 | **H-4** 修复配置保存后服务不重连的 Bug | 30 分钟 |
| 6 | **M-3** 调用 `cleanOldLogs()` 防止存储溢出 | 15 分钟 |
| 7 | **C-1** 移除硬编码凭据 + 加密存储 | 2-4 小时 |
| 8 | **M-2** 修正日志关键词笔误 | 15 分钟 |
