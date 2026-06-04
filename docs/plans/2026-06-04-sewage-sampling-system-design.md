# 污水自动采样系统 Android 客户端设计文档 (Design Document)

* **设计日期**：2026-06-04
* **系统名称**：污水自动采样系统 (Sewage Automatic Sampling System)
* **状态**：已批准 (Approved)

本设计文档归纳自 2026-06-04 的头脑风暴与设计评审，细化了系统架构、数据交互协议与界面导航设计。

---

## 1. 架构方案 (Native Kotlin + Jetpack Compose)

选择**方案 1：原生 Android 开发（Kotlin + Jetpack Compose）**：
* **前台服务 (Foreground Service)** 运行 HiveMQ MQTT Client，常驻系统通知栏，用于维持前后台 MQTT 连接。
* **Jetpack Compose** 用于构建 Material Design 3 风格 UI，支持平滑转场动效与深浅色模式。
* **Repository 模式** 调度底层存储与 MQTT 连接，本地数据主要保存在 App 私有外部沙盒中。

---

## 2. 数据流与协议

### 2.1 主题划分 (参考 pi_water 项目)
1. **状态与诊断信息订阅**：
   - `pi_water/system/status`：系统运行模式状态。
   - `pi_water/system/info`：设备时间、启动时间、Uptime 等诊断信息（`\n` 分隔三行文本）。
2. **远程通道日志订阅**：
   - `pi_water/log/+`（`+` 为通道号 `1`、`2`、`3`）：接收通道状态事件日志（时间、通道号、动作状态以 `\n` 分割三行文本）。
3. **污水照片流订阅**：
   - `pi_water/photo`：原始图片二进制字节流。
4. **采样时长与水泵时间配置发布**：
   - `pi_water/config/duration/+`（`+` 为通道号 `1`、`2`、`3`）：下发通道预期总时长（单位：分钟）。
   - `pi_water/config/pump_time/+`（`+` 为通道号 `1`、`2`、`3`）：下发通道水泵运行时间（单位：秒）。

### 2.2 本地存储
* **位置**：`/sdcard/Android/data/[package_name]/files/`
* **日志**：`logs/log_yyyyMMdd.json`，使用 JSON Lines 格式按行存储。每个日志条目会关联图片文件名及通道编号。
* **图像**：`images/IMG_yyyyMMdd_HHmmss.jpg`，保存从 `pi_water/photo` 接收到的原始图片，并在日志记录的 `image_path` 字段中指向它。

---

## 3. UI 页面划分

系统包含底部导航控制的四个独立页面：
1. **主监控页 (Monitor)**：展示当前连接状态卡片、树莓派系统诊断看板、分通道 (1, 2, 3) 滚动日志监视区及最新回传的污水采样照片。
2. **日志查询页 (Logs)**：根据日期和关键字筛选本地的 JSON Lines 日志流，分通道展示日志内容，可放大预览关联的污水照片。
3. **通信设置页 (MQTT Config)**：配置连接 IP、端口、ClientID 等，默认连接 `voicevon.vicp.io:1883`，提供底层 MQTT 重连状态控制台。
4. **参数设置页 (Parameters)**：支持分别针对通道 1、通道 2、通道 3 进行滑块/输入框微调。包含 expected_duration（分钟）和 pump_work_time（秒），保存后即时通过对应的 config 主题发布至远端。
