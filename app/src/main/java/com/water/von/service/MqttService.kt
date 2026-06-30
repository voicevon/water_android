package com.water.von.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.water.von.MainActivity
import com.water.von.data.LogEntry
import com.water.von.utils.MqttTopics
import com.water.von.data.LogManager
import com.water.von.utils.DataProcessor
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.water.von.ui.components.SensorDataPoint

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
        private val companionJob = SupervisorJob()
        private val companionScope = CoroutineScope(Dispatchers.IO + companionJob)

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

        private val _isMqttDebuggingActive = MutableStateFlow(false)
        val isMqttDebuggingActive: StateFlow<Boolean> = _isMqttDebuggingActive.asStateFlow()

        private val _debugPacketCount = MutableStateFlow(0)
        val debugPacketCount: StateFlow<Int> = _debugPacketCount.asStateFlow()

        val debugDataPoints = Array(4) { mutableStateListOf<SensorDataPoint>() }
        
        val channels = Array(4) { id -> com.water.von.data.SensorChannel(id + 1, thresholdOffset = 50) }

        @Volatile
        private var autoStopJob: Job? = null

        @Volatile
        var activeSensorPrefix: String? = null

        fun startMqttDebugging(context: Context, prefix: String) {
            activeSensorPrefix = prefix
            _debugPacketCount.value = 0
            for (i in 0 until 4) {
                debugDataPoints[i].clear()
                channels[i].reset()
            }
            _isMqttDebuggingActive.value = true
            
            subscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
            publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, prefix, qos = 2)
            
            cancelAutoStopTimer()
        }

        fun stopMqttDebugging(context: Context) {
            if (!_isMqttDebuggingActive.value) return
            _isMqttDebuggingActive.value = false
            
            publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, "stop", qos = 2)
            unsubscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
            activeSensorPrefix = null
            
            cancelAutoStopTimer()
        }

        fun scheduleAutoStopTimer(context: Context) {
            cancelAutoStopTimer()
            autoStopJob = companionScope.launch {
                kotlinx.coroutines.delay(15 * 60 * 1000L) // 15分钟
                stopMqttDebugging(context)
                Log.i("MqttService", "MQTT 调试已因页面离开 15 分钟超时自动停止")
            }
        }

        fun cancelAutoStopTimer() {
            autoStopJob?.cancel()
            autoStopJob = null
        }

        /**
         * 向指定主题发布消息
         */
        fun publish(context: Context, topic: String, payload: String, qos: Int = 1) {
            MqttBus.sendCommand(MqttCommand.Publish(topic, payload, qos))
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

        private val _isBleScanning = MutableStateFlow(false)
        val isBleScanning: StateFlow<Boolean> = _isBleScanning.asStateFlow()

        fun startBleScan(context: Context) {
            val intent = Intent(context, MqttService::class.java).apply {
                action = "com.water.von.ACTION_START_BLE_SCAN"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopBleScan(context: Context) {
            val intent = Intent(context, MqttService::class.java).apply {
                action = "com.water.von.ACTION_STOP_BLE_SCAN"
            }
            context.startService(intent)
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
                    is MqttCommand.Publish -> publishInternal(cmd.topic, cmd.payload, cmd.qos)
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
        } else if (intent?.action == "com.water.von.ACTION_START_BLE_SCAN") {
            startBleScanInternal()
        } else if (intent?.action == "com.water.von.ACTION_STOP_BLE_SCAN") {
            stopBleScanInternal()
        }

        return START_STICKY
    }

    private fun publishInternal(topic: String, payload: String, qos: Int = 1) {
        if (mqttClient?.isConnected == true) {
            try {
                val message = MqttMessage(payload.toByteArray(Charsets.UTF_8))
                message.qos = qos
                message.isRetained = false
                mqttClient?.publish(topic, message)
                // publish() 是异步的，消息已加入 Paho 内部队列，等待 deliveryComplete 回调确认 Broker 已收到
                addConsoleLog("消息已加入发送队列 -> 主题: $topic, QoS: $qos, 内容: $payload")
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
                        
                        if (_isMqttDebuggingActive.value) {
                            _debugPacketCount.value++
                            val physicalChannels = arrayOf(ch4, ch3, ch2, ch1)
                            
                            for (i in 0 until 4) {
                                val rawValue = physicalChannels[i]
                                val state = channels[i].pushRaw(rawValue)
                                val hasWater = state == com.water.von.data.SensorState.HAS_WATER
                                
                                val newPoint = SensorDataPoint(
                                    ch0 = rawValue,
                                    ch1 = channels[i].filteredValue,
                                    ch2 = channels[i].baseline,
                                    ch3 = channels[i].threshold,
                                    hasWater = hasWater
                                )
                                
                                synchronized(debugDataPoints[i]) {
                                    if (debugDataPoints[i].size >= 1000) {
                                        debugDataPoints[i].removeAt(0)
                                    }
                                    debugDataPoints[i].add(newPoint)
                                }
                            }
                        }
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
        val sp = getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        val showPopup = sp.getBoolean("show_notification_popup", true)
        if (!showPopup) {
            return
        }
        val useSound = sp.getBoolean("use_notification_sound", true)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (useSound) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            builder.setSound(null)
        }

        val alarmNotification = builder.build()

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

    private var wakeLock: PowerManager.WakeLock? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private val bleDataProcessors = Array(4) { DataProcessor() }
    private val bleChannelsTriggered = BooleanArray(4) { false }
    private var bleLastSeqNum = -1
    private var bleTimeoutJob: Job? = null

    private fun startBleScanInternal() {
        if (_isBleScanning.value) {
            addConsoleLog("BLE 后台扫描已在运行中")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            addConsoleLog("启动 BLE 扫描失败: 蓝牙未开启或不支持")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            addConsoleLog("启动 BLE 扫描失败: 无法获取 BluetoothLeScanner")
            return
        }

        bleScanner = scanner
        _isBleScanning.value = true
        addConsoleLog("启动 BLE 后台扫描...")

        // 1. 获取 WakeLock，防止锁屏 CPU 休眠
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "von:BleScanWakeLock")
            }
            wakeLock?.acquire(30 * 60 * 1000L /* 30分钟最大保护 */)
            addConsoleLog("已获取 PARTIAL_WAKE_LOCK 唤醒锁")
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }

        // 2. 初始化防抖及触发状态
        bleLastSeqNum = -1
        bleChannelsTriggered.fill(false)
        for (dp in bleDataProcessors) {
            dp.reset()
        }

        // 3. 启动 15 分钟无数据超时定时器
        resetBleTimeoutTimer()

        // 4. 构建 ScanFilter (锁屏后台扫描必须指定过滤条件，否则被系统拦截)
        val filter = ScanFilter.Builder()
            .setManufacturerData(0xFFFF, byteArrayOf(), byteArrayOf()) // 过滤指定厂商 ID 0xFFFF
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)
                result?.scanRecord?.getManufacturerSpecificData(0xFFFF)?.let { data ->
                    if (data.size >= 8) {
                        val ch0 = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val ch1 = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                        val ch2 = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                        val ch3 = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                        
                        val seqNum = if (data.size > 8) data[8].toInt() and 0xFF else -1
                        if (seqNum == -1 || seqNum != bleLastSeqNum) {
                            bleLastSeqNum = seqNum
                            
                            // 重置 15 分钟无数据超时定时器
                            resetBleTimeoutTimer()

                            // 更新全局 Flow 让 UI 实时消费
                            _latestSensorRawData.value = intArrayOf(ch0, ch1, ch2, ch3)

                            // 后台逻辑处理：滤波、基准值计算、触发告警/恢复记录
                            val physicalChannels = arrayOf(ch3, ch2, ch1, ch0)
                            for (i in 0 until 4) {
                                val rawValue = physicalChannels[i]
                                val filteredValue = bleDataProcessors[i].pushRaw(rawValue)
                                val baseline = bleDataProcessors[i].pushBaseline(filteredValue)
                                val threshold = baseline - DataProcessor.THRESHOLD_OFFSET

                                // 阈值触发判断：filteredValue < threshold 表示触发 (即有水/接触液体)
                                val isTriggeredNow = filteredValue < threshold
                                val wasTriggered = bleChannelsTriggered[i]

                                if (isTriggeredNow && !wasTriggered) {
                                    bleChannelsTriggered[i] = true
                                    val uiChannelNum = 4 - i
                                    val message = "传感器通道 Ch$uiChannelNum 触发告警：检测到液体 (当前值: $filteredValue, 阈值: $threshold)"
                                    
                                    // 写入 LogManager 本地归档 (Channel 对应 1..4)
                                    logManager.writeLog(
                                        channel = uiChannelNum,
                                        level = "WARN",
                                        message = message,
                                        imagePath = ""
                                    )
                                    // 发送横幅报警通知
                                    showAlarmNotification("通道 $uiChannelNum 传感器报警", message)
                                    addConsoleLog("BLE告警: $message")
                                } else if (!isTriggeredNow && wasTriggered) {
                                    bleChannelsTriggered[i] = false
                                    val uiChannelNum = 4 - i
                                    val message = "传感器通道 Ch$uiChannelNum 恢复正常：液体消失 (当前值: $filteredValue, 阈值: $threshold)"
                                    
                                    logManager.writeLog(
                                        channel = uiChannelNum,
                                        level = "INFO",
                                        message = message,
                                        imagePath = ""
                                    )
                                    addConsoleLog("BLE恢复: $message")
                                }
                            }
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                addConsoleLog("BLE 后台扫描失败, 错误码: $errorCode")
            }
        }

        try {
            bleScanner?.startScan(listOf(filter), settings, bleScanCallback)
            addConsoleLog("已开始 BLE 后台过滤扫描 (ManufacturerData=0xFFFF)")
        } catch (e: SecurityException) {
            addConsoleLog("启动 BLE 扫描失败: 权限不足 SecurityException")
            stopBleScanInternal()
        } catch (e: Exception) {
            addConsoleLog("启动 BLE 扫描失败: ${e.message}")
            stopBleScanInternal()
        }
    }

    private fun stopBleScanInternal() {
        if (!_isBleScanning.value) return
        _isBleScanning.value = false
        addConsoleLog("停止 BLE 后台扫描...")

        // 1. 停止蓝牙扫描
        try {
            if (bleScanner != null && bleScanCallback != null) {
                bleScanner?.stopScan(bleScanCallback)
                addConsoleLog("已停止 BLE 扫描器")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "停止扫描失败: 权限不足 SecurityException")
        } catch (e: Exception) {
            Log.e(TAG, "停止扫描失败: ${e.message}")
        }
        bleScanCallback = null
        bleScanner = null

        // 2. 取消超时任务
        bleTimeoutJob?.cancel()
        bleTimeoutJob = null

        // 3. 释放 WakeLock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                addConsoleLog("已释放 WakeLock 唤醒锁")
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败: ${e.message}")
        }
        wakeLock = null
    }

    private fun resetBleTimeoutTimer() {
        bleTimeoutJob?.cancel()
        bleTimeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(15 * 60 * 1000L) // 15 分钟
            addConsoleLog("触发 15 分钟无 BLE 数据超时保护，自动关闭扫描")
            stopBleScanInternal()
        }
    }

    override fun onDestroy() {
        stopBleScanInternal()
        shouldReconnect = false
        reconnectJob?.cancel()
        disconnectMqtt()
        serviceJob.cancel()
        companionJob.cancel()
        isServiceRunning = false
        addConsoleLog("MqttService 已销毁")
        super.onDestroy()
    }
}
