package com.water.von.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.water.von.MainActivity
import com.water.von.data.LogEntry
import com.water.von.utils.MqttTopics
import com.water.von.data.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/**
 * 污水采样监控前台常驻网络连接服务
 * 负责后台维持 MQTT 长连接、消息订阅与归档存储
 */
class MqttService : Service() {
    private val TAG = "MqttService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "MqttServiceChannel"
    private val ALARM_CHANNEL_ID = "SewageAlarmChannel"

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var logManager: LogManager
    private var mqttClient: MqttClient? = null
    private var isServiceRunning = false
    private val notificationCounter = java.util.concurrent.atomic.AtomicInteger(2000)

    @Volatile
    private var shouldReconnect = true
    private var reconnectDelay = 2000L // 初始重连延时 2 秒
    private var reconnectJob: Job? = null

    companion object {
        // 使用 Flow 向前台 UI 实时暴露通信状态（私有可变，公有只读）
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _systemStatus = MutableStateFlow("offline")
        val systemStatus: StateFlow<String> = _systemStatus.asStateFlow()

        private val _systemInfo = MutableStateFlow("未接收到数据\n---\n---")
        val systemInfo: StateFlow<String> = _systemInfo.asStateFlow()

        private val _latestLogChannel1 = MutableStateFlow<LogEntry?>(null)
        val latestLogChannel1: StateFlow<LogEntry?> = _latestLogChannel1.asStateFlow()

        private val _latestLogChannel2 = MutableStateFlow<LogEntry?>(null)
        val latestLogChannel2: StateFlow<LogEntry?> = _latestLogChannel2.asStateFlow()

        private val _latestLogChannel3 = MutableStateFlow<LogEntry?>(null)
        val latestLogChannel3: StateFlow<LogEntry?> = _latestLogChannel3.asStateFlow()

        private val _latestPhotoPath = MutableStateFlow<String?>(null)
        val latestPhotoPath: StateFlow<String?> = _latestPhotoPath.asStateFlow()

        private val _mqttLogs = MutableStateFlow<List<String>>(emptyList())
        val mqttLogs: StateFlow<List<String>> = _mqttLogs.asStateFlow()

        private val _latestSensorRawData = MutableStateFlow<IntArray?>(null)
        val latestSensorRawData: StateFlow<IntArray?> = _latestSensorRawData.asStateFlow()

        private val _stationChineseName = MutableStateFlow("济南东站污水厂")
        val stationChineseName: StateFlow<String> = _stationChineseName.asStateFlow()

        fun updateStationChineseName(context: Context, name: String) {
            _stationChineseName.value = name
        }

        private val _stationEnglishName = MutableStateFlow("dongzhan")
        val stationEnglishName: StateFlow<String> = _stationEnglishName.asStateFlow()

        fun updateStationEnglishName(context: Context, name: String) {
            _stationEnglishName.value = name
        }

        @Volatile
        var activeSensorPrefix: String? = null

        /**
         * 向指定主题发布消息
         */
        fun publish(context: Context, topic: String, payload: String) {
            MqttBus.sendCommand(MqttCommand.Publish(topic, payload))
        }

        /**
         * 订阅指定主题
         */
        fun subscribe(context: Context, topic: String) {
            MqttBus.sendCommand(MqttCommand.Subscribe(topic))
        }

        /**
         * 取消订阅指定主题
         */
        fun unsubscribe(context: Context, topic: String) {
            MqttBus.sendCommand(MqttCommand.Unsubscribe(topic))
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // 从 SharedPreferences 中加载并初始化中英文站点名
        val sp = getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        val savedName = sp.getString("station_chinese_name", "济南东站污水厂") ?: "济南东站污水厂"
        _stationChineseName.value = savedName
        val savedEnglishName = sp.getString("prefix_name", "dongzhan") ?: "dongzhan"
        _stationEnglishName.value = savedEnglishName

        logManager = LogManager.getInstance(applicationContext)
        createNotificationChannels()
        serviceScope.launch { logManager.cleanOldLogs(30) }
        serviceScope.launch {
            MqttBus.commands.collect { cmd ->
                when (cmd) {
                    is MqttCommand.Publish -> publishInternal(cmd.topic, cmd.payload)
                    is MqttCommand.Subscribe -> subscribeInternal(cmd.topic)
                    is MqttCommand.Unsubscribe -> unsubscribeInternal(cmd.topic)
                    is MqttCommand.Reconnect -> {
                        shouldReconnect = true
                        connectMqtt()
                    }
                }
            }
        }
        addConsoleLog("MqttService 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            isServiceRunning = true
            startForegroundService()
            shouldReconnect = true
            connectMqtt()
        }

        if (intent?.action == "com.water.von.ACTION_PUBLISH") {
            val topic = intent.getStringExtra("topic")
            val payload = intent.getStringExtra("payload")
            if (topic != null && payload != null) {
                publishInternal(topic, payload)
            }
        } else if (intent?.action == "com.water.von.ACTION_SUBSCRIBE") {
            val topic = intent.getStringExtra("topic")
            if (topic != null) {
                subscribeInternal(topic)
            }
        } else if (intent?.action == "com.water.von.ACTION_UNSUBSCRIBE") {
            val topic = intent.getStringExtra("topic")
            if (topic != null) {
                unsubscribeInternal(topic)
            }
        }

        return START_STICKY
    }

    private fun publishInternal(topic: String, payload: String) {
        if (mqttClient?.isConnected == true) {
            try {
                val message = MqttMessage(payload.toByteArray(Charsets.UTF_8))
                message.qos = 1
                message.isRetained = false
                mqttClient?.publish(topic, message)
                // publish() 是异步的，消息已加入 Paho 内部队列，等待 deliveryComplete 回调确认 Broker 已收到
                addConsoleLog("消息已加入发送队列 -> 主题: $topic, 内容: $payload")
            } catch (e: MqttException) {
                // MqttException 包含 Paho 错误码，比如 reasonCode=32104 表示连接丢失
                addConsoleLog("发布消息失败 [MqttException rc=${e.reasonCode}]: ${e.message}")
            } catch (e: Exception) {
                addConsoleLog("发布消息失败 [${e.javaClass.simpleName}]: ${e.message}")
            }
        } else {
            addConsoleLog("发布消息失败: MQTT 未连接 (isConnected=false)")
        }
    }

    private fun subscribeInternal(topic: String) {
        if (mqttClient?.isConnected == true) {
            try {
                mqttClient?.subscribe(topic, 1)
                addConsoleLog("成功订阅主题: $topic")
            } catch (e: Exception) {
                addConsoleLog("订阅主题失败 [$topic]: ${e.message}")
            }
        } else {
            addConsoleLog("订阅主题失败: MQTT 未连接 ($topic)")
        }
    }

    private fun unsubscribeInternal(topic: String) {
        if (mqttClient?.isConnected == true) {
            try {
                mqttClient?.unsubscribe(topic)
                addConsoleLog("成功取消订阅主题: $topic")
            } catch (e: Exception) {
                addConsoleLog("取消订阅主题失败 [$topic]: ${e.message}")
            }
        } else {
            addConsoleLog("取消订阅主题失败: MQTT 未连接 ($topic)")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * 添加控制台网络调试日志
     */
    private fun addConsoleLog(message: String) {
        Log.i(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _mqttLogs.update { current ->
            (listOf("[$timestamp] $message") + current).take(100)
        }
    }

    /**
     * 开启前台通知栏常驻
     */
    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("污水采样监控中")
            .setContentText("已启用后台长连接服务")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14 (API 34+)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 创建系统通知渠道
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "MQTT后台服务通知",
                NotificationManager.IMPORTANCE_LOW
            )
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "污水采集警告警报",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "接收来自现场污水采样机的警报和关键动作事件通知"
                enableLights(true)
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(alarmChannel)
        }
    }

    /**
     * 连接 MQTT 代理服务器（内置互斥取消防止重连风暴）
     */
    private fun connectMqtt() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val sharedPreferences = com.water.von.utils.SecurePrefs.get(applicationContext)
            val brokerIp = sharedPreferences.getString("broker_ip", "voicevon.vicp.io") ?: "voicevon.vicp.io"
            val brokerPort = sharedPreferences.getInt("broker_port", 1883)
            val username = sharedPreferences.getString("username", "von") ?: "von"
            val password = sharedPreferences.getString("password", "von123456") ?: "von123456"
            val customClientId = sharedPreferences.getString("client_id", "") ?: ""
            
            val brokerUrl = "tcp://$brokerIp:$brokerPort"
            val clientId = if (customClientId.isNotEmpty()) customClientId else "WaterAndroid_" + UUID.randomUUID().toString().substring(0, 8)

            addConsoleLog("尝试连接至 MQTT Broker: $brokerUrl...")
            try {
                try {
                    if (mqttClient?.isConnected == true) mqttClient?.disconnect()
                    mqttClient?.close()
                } catch (_: Exception) {}
                mqttClient = null
                
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
                val options = MqttConnectOptions().apply {
                    userName = username
                    this.password = password.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        _isConnected.value = true
                        reconnectDelay = 2000L // 重置重连延迟
                        addConsoleLog("MQTT 连接成功: $serverURI")
                        subscribeToTopics()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        _isConnected.value = false
                        addConsoleLog("MQTT 连接丢失: ${cause?.message}")
                        if (shouldReconnect) {
                            triggerReconnection()
                        }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic != null && message != null) {
                            handleIncomingMessage(topic, message)
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Broker 已确认收到 QoS=1 消息（PUBACK 已到达）
                        try {
                            val msg = token?.message
                            val payload = msg?.let { String(it.payload, Charsets.UTF_8) } ?: "<unknown>"
                            addConsoleLog("Broker 已确认收到消息 ✓ payload=$payload")
                        } catch (_: Exception) {
                            addConsoleLog("Broker 已确认收到消息 ✓")
                        }
                    }
                })

                mqttClient?.connect(options)

            } catch (e: Exception) {
                addConsoleLog("MQTT 连接失败异常: ${e.message}")
                _isConnected.value = false
                if (shouldReconnect) {
                    triggerReconnection()
                }
            }
        }
    }

    /**
     * 订阅相关主题
     */
    private fun subscribeToTopics() {
        try {
            mqttClient?.subscribe(MqttTopics.SYSTEM_STATUS, 1)
            mqttClient?.subscribe(MqttTopics.SYSTEM_INFO, 1)
            mqttClient?.subscribe(MqttTopics.PHOTO_WILDCARD, 1)
            mqttClient?.subscribe(MqttTopics.LOG_WILDCARD, 1) // 通配符订阅 1, 2, 3 通道日志
            mqttClient?.subscribe(MqttTopics.SENSOR_STATUS_TOPIC, 1) // 订阅全局水传感器上报数据主题
            addConsoleLog("已成功订阅监控主题队列")
        } catch (e: Exception) {
            addConsoleLog("订阅主题失败: ${e.message}")
        }
    }

    /**
     * 触发指数避退延迟重连（内置任务互斥防止风暴）
     */
    private fun triggerReconnection() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            addConsoleLog("计划在 ${reconnectDelay / 1000} 秒后重新连接...")
            kotlinx.coroutines.delay(reconnectDelay)
            // 每次重连失败，重连延迟翻倍，最大延迟60秒
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(60000L)
            if (shouldReconnect) {
                connectMqtt()
            }
        }
    }

    /**
     * 处理收到的 MQTT 监控报文
     */
    private fun handleIncomingMessage(topic: String, message: MqttMessage) {
        val payloadBytes = message.payload
        
        when {
            topic == MqttTopics.SENSOR_STATUS_TOPIC -> {
                try {
                    val jsonStr = String(payloadBytes, Charsets.UTF_8)
                    val json = org.json.JSONObject(jsonStr)
                    val name = json.optString("name", "")
                    if (name.isNotEmpty() && name == activeSensorPrefix) {
                        val ch1 = json.optInt("ch1", 0)
                        val ch2 = json.optInt("ch2", 0)
                        val ch3 = json.optInt("ch3", 0)
                        val ch4 = json.optInt("ch4", 0)
                        _latestSensorRawData.value = intArrayOf(ch1, ch2, ch3, ch4)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse sensor status data: ${e.message}", e)
                }
            }

            topic == MqttTopics.SYSTEM_STATUS -> {
                val status = String(payloadBytes, Charsets.UTF_8)
                _systemStatus.value = status
            }
            
            topic == MqttTopics.SYSTEM_INFO -> {
                val info = String(payloadBytes, Charsets.UTF_8)
                _systemInfo.value = info
            }

            topic.startsWith("water/photo/status/") -> {
                val stationName = topic.substringAfter("water/photo/status/")
                if (stationName != _stationEnglishName.value) {
                    addConsoleLog("收到其他站点的图片回传 ($stationName)，已忽略")
                    return
                }
                if (payloadBytes.size > 5 * 1024 * 1024) { // 5MB 上限
                    addConsoleLog("图片过大，已忽略: ${payloadBytes.size} bytes")
                    return
                }
                serviceScope.launch {
                    val relativePath = logManager.savePhoto(payloadBytes)
                    if (relativePath.isNotEmpty()) {
                        _latestPhotoPath.value = relativePath
                        // 同时将新图片写入日志归档记录中（假定当前触发的是活跃的泵启动）
                        logManager.writeLog(
                            channel = 1, // 缺省至通道 1
                            level = "INFO",
                            message = "接收到污水图片快照",
                            imagePath = relativePath
                        )
                    }
                }
            }

            topic.startsWith(MqttTopics.LOG_PREFIX) -> {
                val channelStr = topic.substringAfter(MqttTopics.LOG_PREFIX)
                val channelId = channelStr.toIntOrNull() ?: 1
                val logPayload = String(payloadBytes, Charsets.UTF_8)
                
                // pi_water 日志消息通常由 \n 拆分为三行。我们提取第三行的内容作为具体动作消息
                val lines = logPayload.split("\n")
                val actionMessage = if (lines.size >= 3) lines[2] else logPayload
                
                val level = if (actionMessage.contains("ERROR") || actionMessage.contains("异常") || actionMessage.contains("失败")) {
                    "ERROR"
                } else if (actionMessage.contains("警告") || actionMessage.contains("丢")) {
                    "WARN"
                } else {
                    "INFO"
                }

                // 写入本地归档日志
                val logEntry = logManager.writeLog(
                    channel = channelId,
                    level = level,
                    message = actionMessage,
                    imagePath = ""
                )

                // 实时推送通知并更新 UI flow
                updateChannelFlow(channelId, logEntry)
                
                // 若包含警告级别，立即发横幅报警通知
                if (level == "ERROR" || level == "WARN") {
                    showAlarmNotification("通道 $channelId 状态警告", actionMessage)
                }
            }
        }
    }

    private fun updateChannelFlow(channelId: Int, logEntry: LogEntry) {
        when (channelId) {
            1 -> _latestLogChannel1.value = logEntry
            2 -> _latestLogChannel2.value = logEntry
            3 -> _latestLogChannel3.value = logEntry
        }
    }

    /**
     * 发送高优先级横幅警报通知
     */
    private fun showAlarmNotification(title: String, text: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val alarmNotification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationCounter.getAndIncrement(), alarmNotification)
    }

    private fun disconnectMqtt() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
            }
            mqttClient?.close()
            mqttClient = null
            _isConnected.value = false
            addConsoleLog("已断开 MQTT 连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        shouldReconnect = false
        reconnectJob?.cancel()
        disconnectMqtt()
        serviceJob.cancel()
        isServiceRunning = false
        addConsoleLog("MqttService 已销毁")
        super.onDestroy()
    }
}
