# 污水自动采样系统 - 摄像头 MQTT 通信协议与拍照控制逻辑规范

| 项目名称 | 摄像头 MQTT 通信协议与拍照控制逻辑规范 |
| :--- | :--- |
| 文档版本 | V1.0.0 |
| 创建日期 | 2026-06-27 |
| 项目状态 | 草稿 / 规划中 |
| 协议类型 | MQTT (Message Queuing Telemetry Transport) |

---

## 1. 概述

本规范旨在定义**污水自动采样系统**中，手机端客户端（Mobile App）与部署在各个采样站点的摄像头节点（Camera Nodes，例如 ESP32-S3 摄像头模块）之间的 MQTT 通信协议与拍照业务控制流程。

通过本协议，手机端可以向特定站点下发拍照指令，摄像头节点执行拍照并回传图像数据，手机端根据当前激活的目标站点进行过滤显示，实现多站点摄像头的有序调度与按需拍摄。

---

## 2. MQTT 通信角色与拓扑

在摄像头拍照通信中，手机端与摄像头节点分别承担以下角色：

```mermaid
graph TD
    App[手机客户端 / 控制端] -- "① 发布拍照指令<br>Topic: water/photo/take<br>Payload: 站点英文名" --> Broker[MQTT Broker]
    Broker -- "② 订阅并接收指令" --> CameraNodes[摄像头节点群<br>如: dongzhan, home, ...]
    CameraNodes -- "③ 执行拍照并发布图片数据<br>Topic: water/photo/status/站点英文名" --> Broker
    Broker -- "④ 订阅通配符主题并接收图片<br>Topic: water/photo/status/+" --> App
```

| 通信主题 | 发布者 (Publisher) | 订阅者 (Subscriber) | 用途 |
| :--- | :--- | :--- | :--- |
| `water/photo/take` | 手机客户端 | 摄像头节点群 (所有节点订阅) | 下发拍照控制指令，载荷为目标站点英文名 |
| `water/photo/status/<station_name>` | 摄像头节点 | 手机客户端 (订阅 `water/photo/status/+`) | 回传拍摄的实时照片数据，主题末尾包含其站点英文名 |

---

## 3. 主题与消息格式规范

### 3.1 拍照控制主题 (`water/photo/take`)

* **主题名称**：`water/photo/take`
* **流向**：Publisher（手机客户端 / 云端调度服务） $\rightarrow$ 摄像头节点群 (QoS = 1)
* **载荷格式**：JSON 字符串
* **字段定义**：
  * `site_name` (String, 必填)：目标站点英文名，如 `"dongzhan"`、`"home"`
  * `action` (String, 可选)：即时动作，`"capture"` 代表立即拍摄一张并上报
  * `motion` (String/Boolean, 可选)：异物侵入检测守护开关，`"on"` / `true`（开启突变检测并自动报警），`"off"` / `false`（关闭突变检测，保持纯静默待命）
* **架构设计原则**：
  * **动作与开关彻底解耦**：移除人为定义的虚假“运行模式”；相机底色为待命，收到 `action: "capture"` 立即拍照；`motion` 纯粹作为辅助守护开关；
  * **定时职责归位 Publisher**：ESP32 摄像头节点自身无定时拍照循环。需要定时/周期性巡视照片的业务方（如手机 App 后台任务、定时服务），由其自身的定时器按周期发布 `{"site_name":"...","action":"capture"}` 指令；
  * 摄像头节点收到消息后，匹配 `site_name`，执行拍摄并回传；若不一致则忽略。

---

### 3.2 拍照状态回传主题 (`water/photo/status/<station_name>`)

* **主题名称**：`water/photo/status/<station_name>` （例如：`water/photo/status/dongzhan`）
* **流向**：摄像头节点 $\rightarrow$ 手机客户端 (QoS = 1)
* **载荷格式**：二进制字节流 (Binary / Image bytes, JPG 格式)
* **内容定义**：摄像头拍摄并压缩后的 JPG 照片原始二进制数据。
* **逻辑说明**：
  * 手机端需要订阅带有单级通配符的主题：`water/photo/status/+`。
  * 这样，手机端可以接收到所有站点回传的照片，然后通过匹配主题中的 `<station_name>` 进行过滤。

---

## 4. 摄像头节点与手机端控制逻辑

### 4.1 摄像头节点控制逻辑

每个摄像头节点在接收到控制指令后的执行逻辑如下：

```mermaid
flowchart TD
    Start([节点初始化]) --> SubTake[订阅主题: water/photo/take]
    SubTake --> WaitMsg{等待接收消息}
    
    WaitMsg -- 收到消息 --> CheckPayload{比对消息载荷 Payload}
    CheckPayload -- "Payload == 自身站点英文名" --> Capture[触发摄像头拍照并压缩为 JPG]
    CheckPayload -- "Payload != 自身站点英文名" --> Ignore[忽略消息并保持静默]
    
    Capture --> PublishImage[发布图片数据至 water/photo/status/自身站点名]
    PublishImage --> WaitMsg
    Ignore --> WaitMsg
```

---

### 4.2 手机端接收逻辑

手机端在订阅通配符主题 `water/photo/status/+` 后，处理收到的照片数据的逻辑如下：

```mermaid
flowchart TD
    AppStart([手机端订阅通配符主题 water/photo/status/+]) --> WaitImage{等待接收图片消息}
    WaitImage -- 收到消息 --> ExtractStation[解析 Topic 最后一级的站点英文名 station_name]
    ExtractStation --> CheckTarget{比对：station_name == 当前全局目标站点英文名?}
    CheckTarget -- "一致" --> Render[将二进制图片数据按正常流程在界面展示]
    CheckTarget -- "不一致" --> Discard[丢弃当前图片载荷 Payload]
    
    Render --> WaitImage
    Discard --> WaitImage
```

---

## 5. 验证与测试方法

### 5.1 命令行模拟测试

在开发或联调阶段，可使用 `mosquitto_pub` 与 `mosquitto_sub` 命令行工具进行协议行为验证：

1. **手机端模拟下发拍照指令给特定站点 `dongzhan`**：
   ```bash
   mosquitto_pub -h voicevon.vicp.io -t water/photo/take -m "dongzhan"
   ```
2. **验证摄像头节点响应**：
   * 观察 `dongzhan` 站点的摄像头是否被触发拍照。
   * 观察其是否向 `water/photo/status/dongzhan` 发布二进制图片数据。
3. **模拟手机端订阅并接收图片并保存为本地 file**：
   * 模拟订阅接收：
     ```bash
     mosquitto_sub -h voicevon.vicp.io -t water/photo/status/+ -C 1 > received_photo.jpg
     ```
