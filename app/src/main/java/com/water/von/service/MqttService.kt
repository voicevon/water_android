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
import android.speech.tts.TextToSpeech
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import com.water.von.ui.components.SensorDataPoint
import com.water.von.data.SensorState
import com.water.von.detection.MutationDetector

data class StateUpdateEvent(
    val sensorId: Int,
    val stage: Int,
    val duration: Float,
    val pumpTime: Int,
    val elapsed: Int
)

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

    /** MQTT 常驻保活锁：与 BLE 的 wakeLock 分开独立，防止互相影响 */
    private var mqttWakeLock: PowerManager.WakeLock? = null
    /** Wi-Fi 高性能锁：防止 Wi-Fi 降频或休眠断网（仅对 Wi-Fi 连接生效，4G/5G 无效） */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    @Volatile
    private var shouldReconnect = true
    private var reconnectDelay = 2000L // 初始重连延时 2 秒
    private var reconnectJob: Job? = null

    /**
     * 定时巡视拍照任务（对应 ESP32 mut_interval_sec 参数）
     * 按配置间隔向 water/photo/take 发布指令，触发 ESP32 摄像头拍照
     */
    private var periodicPhotoJob: Job? = null

    /**
     * 图像突变检测器（Service 实例级单例，持有 20 帧滑动窗口状态）
     * 算法与 ESP32 端 MutationDetector 完全一致
     */
    private val mutationDetector = MutationDetector()



    companion object {
        // --- Doze 心跳常量 ---
        /** MqttHeartbeatReceiver 向 Service 发送心跳检查的 Action */
        const val ACTION_HEARTBEAT_CHECK = "com.water.von.ACTION_HEARTBEAT_CHECK"
        /** 心跳间隔 8 分钟，接近 Android 9+ Doze 维护窗口最小间隔（系统自动顺延到满足最小 9 分钟） */
        private const val HEARTBEAT_INTERVAL_MS = 8 * 60 * 1000L
        /** CameraSettingsScreen 保存配置后通知 Service 更新定时拍照任务的 Action */
        const val ACTION_UPDATE_PERIODIC_PHOTO = "com.water.von.ACTION_UPDATE_PERIODIC_PHOTO"

        private val _activeAlarmsState = MutableStateFlow<Set<Int>>(emptySet())
        val activeAlarmsState: StateFlow<Set<Int>> = _activeAlarmsState.asStateFlow()

        // 活跃告警通道集合（线程安全），分别追踪本地与远端告警来源
        private val activeAlarms = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        private val activeLocalAlarms = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        private val activeRemoteAlarms = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        private var alarmRepeatingJob: Job? = null

        // 告警边沿检测状态跟踪（放在伴生对象中，避免 Service 重建后残留）
        val mqttLastLocalStates = Array(3) { SensorState.NO_WATER }
        val mqttLastRemoteStates = Array(3) { SensorState.NO_WATER }
        val bleLastLocalStates = Array(3) { SensorState.NO_WATER }
        val bleLastRemoteStates = Array(3) { SensorState.NO_WATER }

        @Volatile
        private var tts: TextToSpeech? = null

        fun initTts(context: Context) {
            if (tts == null) {
                tts = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = java.util.Locale.CHINESE
                    }
                }
            }
        }

        fun shutdownTts() {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {}
            tts = null
        }

        fun speakAlert(context: Context, text: String) {
            val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
            val useSound = sp.getBoolean("use_notification_sound", true)
            if (useSound) {
                try {
                    initTts(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "water_alarm_tts")
                    } else {
                        @Suppress("DEPRECATION")
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
                    }
                } catch (e: Exception) {
                    Log.e("MqttService", "TTS 播报失败: ${e.message}")
                }
            }
        }

        fun stopSpeech() {
            try {
                tts?.stop()
            } catch (_: Exception) {}
        }

        fun triggerAlarm(context: Context, channelNum: Int, isLocal: Boolean) {
            if (isLocal) activeLocalAlarms.add(channelNum) else activeRemoteAlarms.add(channelNum)
            activeAlarms.add(channelNum)
            _activeAlarmsState.value = activeAlarms.toSet()
            startRepeatingAlarm(context)
        }

        private fun startRepeatingAlarm(context: Context) {
            if (alarmRepeatingJob != null) return
            alarmRepeatingJob = companionScope.launch {
                while (isActive && activeAlarms.isNotEmpty()) {
                    val text = activeAlarms.sorted().joinToString("，") { ch ->
                        when {
                            activeLocalAlarms.contains(ch) && activeRemoteAlarms.contains(ch) -> "通道${ch}本地及远端有水"
                            activeLocalAlarms.contains(ch) -> "通道${ch}本地有水"
                            else -> "通道${ch}远端有水"
                        }
                    }
                    speakAlert(context, text)
                    kotlinx.coroutines.delay(5000L)
                }
                alarmRepeatingJob = null
            }
        }

        fun clearAlarms(context: Context) {
            activeAlarms.clear()
            activeLocalAlarms.clear()
            activeRemoteAlarms.clear()
            _activeAlarmsState.value = emptySet()
            alarmRepeatingJob?.cancel()
            alarmRepeatingJob = null
            stopSpeech()
        }

        @Volatile
        private var companionJob = SupervisorJob()
        private val companionScope: CoroutineScope
            get() {
                if (companionJob.isCancelled) companionJob = SupervisorJob()
                return CoroutineScope(Dispatchers.IO + companionJob)
            }

        // 使用 Flow 向前台 UI 实时暴露通信状态（私有可变，公有只读）
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _systemStatus = MutableStateFlow("offline")
        val systemStatus: StateFlow<String> = _systemStatus.asStateFlow()

        private val _stateUpdateFlow = MutableStateFlow<StateUpdateEvent?>(null)
        val stateUpdateFlow: StateFlow<StateUpdateEvent?> = _stateUpdateFlow.asStateFlow()

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

        private val _stationChineseName = MutableStateFlow("工厂之家")
        val stationChineseName: StateFlow<String> = _stationChineseName.asStateFlow()

        fun updateStationChineseName(context: Context, name: String) {
            _stationChineseName.value = name
        }

        private val _stationEnglishName = MutableStateFlow("home")
        val stationEnglishName: StateFlow<String> = _stationEnglishName.asStateFlow()

        fun updateStationEnglishName(context: Context, name: String) {
            val oldName = _stationEnglishName.value
            _stationEnglishName.value = name
            // 站点名变更后触发 MQTT 重新订阅（旧主题取消、新主题订阅）
            if (oldName != name) {
                MqttBus.sendCommand(MqttCommand.Reconnect)
            }
        }

        private val _isMqttDebuggingActive = MutableStateFlow(false)
        val isMqttDebuggingActive: StateFlow<Boolean> = _isMqttDebuggingActive.asStateFlow()

        private val _mqttDebugInterval = MutableStateFlow(1)
        val mqttDebugInterval: StateFlow<Int> = _mqttDebugInterval.asStateFlow()

        private val _debugPacketCount = MutableStateFlow(0)
        val debugPacketCount: StateFlow<Int> = _debugPacketCount.asStateFlow()

        private val _debugPacketCountBle = MutableStateFlow(0)
        val debugPacketCountBle: StateFlow<Int> = _debugPacketCountBle.asStateFlow()

        val debugDataPoints = Array(3) { MutableStateFlow<List<SensorDataPoint>>(emptyList()) }
        val debugDataPointsBle = Array(3) { MutableStateFlow<List<SensorDataPoint>>(emptyList()) }
        
        val channelsMqtt = Array(3) { id -> com.water.von.data.SensorChannel(id + 1, thresholdOffset = 50) }
        val channelsBle = Array(3) { id -> com.water.von.data.SensorChannel(id + 1, thresholdOffset = 50) }

        @Volatile
        private var autoStopJob: Job? = null

        @Volatile
        var activeSensorPrefix: String? = null

        fun startMqttDebugging(context: Context, prefix: String, intervalSeconds: Int) {
            activeSensorPrefix = prefix
            _isMqttDebuggingActive.value = true
            _mqttDebugInterval.value = intervalSeconds
            
            // 将调试激活状态和参数持久化写入 SharedPreferences，以便进程重启后可在 onCreate 中恢复
            val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
            sp.edit().apply {
                putBoolean("mqtt_debugging_active", true)
                putString("active_sensor_prefix", prefix)
                putInt("mqtt_debug_interval", intervalSeconds)
                apply()
            }
            
            subscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
            
            // 构建启动调试的 JSON 消息
            val json = org.json.JSONObject().apply {
                put("name", prefix)
                put("interval", intervalSeconds)
            }
            publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, json.toString(), qos = 2)
            
            cancelAutoStopTimer()
        }

        fun stopMqttDebugging(context: Context) {
            if (!_isMqttDebuggingActive.value) return
            _isMqttDebuggingActive.value = false
            
            // 持久化保存关闭状态到 SharedPreferences
            val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
            sp.edit().apply {
                putBoolean("mqtt_debugging_active", false)
                putString("active_sensor_prefix", null)
                apply()
            }
            
            // 构建全局停止调试的 JSON 消息
            val json = org.json.JSONObject().apply {
                put("command", "stop")
            }
            publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, json.toString(), qos = 2)
            unsubscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
            activeSensorPrefix = null
            
            cancelAutoStopTimer()
        }

        fun scheduleAutoStopTimer(context: Context) {
            // 已根据用户需求禁用 15 分钟离开调试页自动关闭 MQTT 调试的功能
        }

        fun cancelAutoStopTimer() {
            autoStopJob?.cancel()
            autoStopJob = null
        }

        /**
         * 向指定主题发布消息
         */
        fun publish(context: Context, topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
            MqttBus.sendCommand(MqttCommand.Publish(topic, payload, qos, retained))
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

        /**
         * 手动清除 MQTT 和 BLE 调试缓冲区的内存及本地持久化历史文件
         */
        fun clearHistory(context: Context) {
            _debugPacketCount.value = 0
            _debugPacketCountBle.value = 0
            for (i in 0 until 3) {
                debugDataPoints[i].value = emptyList()
                debugDataPointsBle[i].value = emptyList()
                channelsMqtt[i].reset()
                channelsBle[i].reset()
                mqttLastLocalStates[i] = SensorState.NO_WATER
                mqttLastRemoteStates[i] = SensorState.NO_WATER
                bleLastLocalStates[i] = SensorState.NO_WATER
                bleLastRemoteStates[i] = SensorState.NO_WATER
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val fileMqtt = java.io.File(context.filesDir, "sensor_debug_mqtt.json")
                    if (fileMqtt.exists()) fileMqtt.delete()
                    val fileBle = java.io.File(context.filesDir, "sensor_debug_ble.json")
                    if (fileBle.exists()) fileBle.delete()
                } catch (e: Exception) {
                    Log.e("MqttService", "Failed to delete history files", e)
                }
            }
        }

        /**
         * 更新通道施密特触发器偏置值，同步 SharedPreferences 并对历史数据窗口中的阈值进行重新计算及保存
         */
        fun updateChannelOffset(context: Context, channelIndex: Int, newOffset: Int) {
            if (channelIndex !in 0 until 3) return
            
            // 1. 更新内存计算器
            channelsMqtt[channelIndex].thresholdOffset = newOffset
            channelsBle[channelIndex].thresholdOffset = newOffset
            
            // 2. 持久化存储到偏好设置
            val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
            sp.edit().putInt("channel_offset_$channelIndex", newOffset).apply()
            
            // 3. 重新计算 MQTT 历史点阈值
            debugDataPoints[channelIndex].update { list ->
                list.map { point ->
                    point.copy(ch3 = if (point.hasWater) point.ch2 - newOffset else point.ch2 + newOffset)
                }
            }
            
            // 4. 重新计算 BLE 历史点阈值
            debugDataPointsBle[channelIndex].update { list ->
                list.map { point ->
                    point.copy(ch3 = if (point.hasWater) point.ch2 - newOffset else point.ch2 + newOffset)
                }
            }
            
            // 5. 异步保存到本地 JSON 文件，确保连续性
            companionScope.launch(Dispatchers.IO) {
                try {
                    val currentMqtt = Array(3) { debugDataPoints[it].value }
                    com.water.von.utils.SensorDataPersistence.saveDataPoints(context, "sensor_debug_mqtt.json", currentMqtt)
                    
                    val currentBle = Array(3) { debugDataPointsBle[it].value }
                    com.water.von.utils.SensorDataPersistence.saveDataPoints(context, "sensor_debug_ble.json", currentBle)
                } catch (e: Exception) {
                    Log.e("MqttService", "Failed to save data points after recalculating offset", e)
                }
            }
        }

        /**
         * 通知 MqttService 重新读取 SharedPreferences 并更新定时拍照任务
         * 由 CameraSettingsScreen 保存配置后调用
         */
        fun applyPeriodicPhotoSettings(context: Context) {
            val intent = Intent(context, MqttService::class.java).apply {
                action = ACTION_UPDATE_PERIODIC_PHOTO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // 从 SharedPreferences 中加载并初始化中英文站点名
        val sp = getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        val savedName = sp.getString("station_chinese_name", "工厂之家") ?: "工厂之家"
        _stationChineseName.value = savedName
        val savedEnglishName = sp.getString("prefix_name", "home") ?: "home"
        _stationEnglishName.value = savedEnglishName

        // 从 SharedPreferences 中自动加载并恢复 MQTT 后台调试激活状态与参数，防止进程重启导致状态重置
        val isActive = sp.getBoolean("mqtt_debugging_active", false)
        _isMqttDebuggingActive.value = isActive
        if (isActive) {
            activeSensorPrefix = sp.getString("active_sensor_prefix", savedEnglishName) ?: savedEnglishName
            _mqttDebugInterval.value = sp.getInt("mqtt_debug_interval", 1)
        }

        // 加载恢复 4 通道的施密特触发器偏置值 offset
        for (i in 0 until 3) {
            val offset = sp.getInt("channel_offset_$i", 50)
            channelsMqtt[i].thresholdOffset = offset
            channelsBle[i].thresholdOffset = offset
        }

        logManager = LogManager.getInstance(applicationContext)
        createNotificationChannels()
        serviceScope.launch { logManager.cleanOldLogs(30) }
        serviceScope.launch {
            MqttBus.commands.collect { cmd ->
                when (cmd) {
                    is MqttCommand.Publish -> publishInternal(cmd.topic, cmd.payload, cmd.qos, cmd.retained)
                    is MqttCommand.Subscribe -> subscribeInternal(cmd.topic)
                    is MqttCommand.Unsubscribe -> unsubscribeInternal(cmd.topic)
                    is MqttCommand.Reconnect -> {
                        shouldReconnect = true
                        connectMqtt()
                    }
                }
            }
        }

        // 初始化 TTS 语音引擎
        initTts(applicationContext)

        // 从本地加载 MQTT 和 BLE 的历史调试数据，并恢复计数器
        serviceScope.launch {
            val savedMqtt = com.water.von.utils.SensorDataPersistence.loadDataPoints(applicationContext, "sensor_debug_mqtt.json")
            val savedBle = com.water.von.utils.SensorDataPersistence.loadDataPoints(applicationContext, "sensor_debug_ble.json")
            
            var maxCountMqtt = 0
            var maxCountBle = 0
            for (i in 0 until 3) {
                if (savedMqtt[i].isNotEmpty()) {
                    debugDataPoints[i].value = savedMqtt[i]
                    maxCountMqtt = maxCountMqtt.coerceAtLeast(savedMqtt[i].size)
                    // 用历史数据对 channelsMqtt[i] 的滤波与基准线计算器进行热身（最多加载最后 200 个数据点）
                    val warmUpPoints = savedMqtt[i].takeLast(200)
                    warmUpPoints.forEach { point ->
                        channelsMqtt[i].pushRaw(point.ch0)
                    }
                }
                if (savedBle[i].isNotEmpty()) {
                    debugDataPointsBle[i].value = savedBle[i]
                    maxCountBle = maxCountBle.coerceAtLeast(savedBle[i].size)
                    // 用历史数据对 channelsBle[i] 的滤波与基准线计算器进行热身（最多加载最后 200 个数据点）
                    val warmUpPoints = savedBle[i].takeLast(200)
                    warmUpPoints.forEach { point ->
                        channelsBle[i].pushRaw(point.ch0)
                    }
                }
            }
            _debugPacketCount.value = maxCountMqtt
            _debugPacketCountBle.value = maxCountBle
        }

        // 监听调试激活状态，激活时重置 MQTT 历史边缘状态（本地与远端均重置）
        serviceScope.launch {
            _isMqttDebuggingActive.collect { active ->
                if (active) {
                    for (i in 0 until 3) {
                        mqttLastLocalStates[i] = SensorState.NO_WATER
                        mqttLastRemoteStates[i] = SensorState.NO_WATER
                    }
                }
            }
        }

        // 恢复定时巡视拍照任务（若上次保存时已启用）
        startPeriodicPhotoTaskIfEnabled()

        addConsoleLog("MqttService 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            isServiceRunning = true
            startForegroundService()
            acquireMqttLocks()  // 获取 MQTT 专属 WakeLock + WifiLock，防止 Doze 冻结连接
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
        } else if (intent?.action == "com.water.von.ACTION_CLEAR_ALARMS") {
            clearAlarms(applicationContext)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancelAll()
        } else if (intent?.action == ACTION_HEARTBEAT_CHECK) {
            // AlarmManager Doze 唤醒心跳检查
            if (mqttClient?.isConnected == true) {
                addConsoleLog("[Doze心跳] MQTT 连接正常，继续保持")
            } else {
                addConsoleLog("[Doze心跳] MQTT 已断开，触发重连")
                if (shouldReconnect) connectMqtt()
            }
            // 链式续期：调度下一次心跳
            scheduleNextHeartbeat()
        } else if (intent?.action == ACTION_UPDATE_PERIODIC_PHOTO) {
            // CameraSettingsScreen 保存配置后触发，重启定时拍照任务
            periodicPhotoJob?.cancel()
            periodicPhotoJob = null
            mutationDetector.reset()  // 重置滑动窗口，避免参数变更导致误报
            startPeriodicPhotoTaskIfEnabled()
        }

        return START_STICKY
    }

    private fun publishInternal(topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
        if (mqttClient?.isConnected == true) {
            try {
                val message = MqttMessage(payload.toByteArray(Charsets.UTF_8))
                message.qos = qos
                message.isRetained = retained
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
                    keepAliveInterval = 20  // 降至 20 秒：Broker 超时判断 = 20×1.5 = 30 秒，为 AlarmManager 心跳留出足够窗口
                }

                mqttClient?.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        _isConnected.value = true
                        reconnectDelay = 2000L // 重置重连延迟
                        addConsoleLog("MQTT 连接成功: $serverURI")
                        subscribeToTopics()
                        scheduleNextHeartbeat() // 连接成功后立即注册 AlarmManager Doze 心跳
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
            mqttClient?.subscribe(MqttTopics.SYSTEM_STATE, 1) // 订阅全新的结构化流程状态主题
            mqttClient?.subscribe(MqttTopics.SENSOR_STATUS_TOPIC, 1) // 订阅全局水传感器上报数据主题
            // 告警专用 Retained Topic：重连后 Broker 会立即补发未清除的告警，实现断线期间告警零丢失
            mqttClient?.subscribe("${MqttTopics.PREFIX}/alarm", 1)
            addConsoleLog("已成功订阅监控主题队列（含 Retained 告警主题）")
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
                        val ch1 = json.optInt("sensor1", 0)
                        val ch2 = json.optInt("sensor2", 0)
                        val ch3 = json.optInt("sensor3", 0)
                        val stateByte = json.optInt("state", 0)
                        
                        _latestSensorRawData.value = intArrayOf(ch1, ch2, ch3, stateByte)
                        
                        if (_isMqttDebuggingActive.value) {
                            _debugPacketCount.value++
                            val physicalChannels = arrayOf(ch1, ch2, ch3)
                            
                            for (i in 0 until 3) {
                                val rawValue = physicalChannels[i]
                                val stateLocal = channelsMqtt[i].pushRaw(rawValue)
                                val hasWaterLocal = stateLocal == SensorState.HAS_WATER
                                val hasWaterRemote = (stateByte and (1 shl i)) != 0
                                val currentRemoteState = if (hasWaterRemote) SensorState.HAS_WATER else SensorState.NO_WATER

                                val newPoint = SensorDataPoint(
                                    ch0 = rawValue,
                                    ch1 = channelsMqtt[i].filteredValue,
                                    ch2 = channelsMqtt[i].baseline,
                                    ch3 = channelsMqtt[i].threshold,
                                    hasWater = hasWaterLocal,
                                    hasWaterRemote = hasWaterRemote
                                )

                                debugDataPoints[i].update { current ->
                                    val list = if (current.size >= 1020) current.drop(1) else current
                                    list + newPoint
                                }

                                val uiChannelNum = i + 1

                                // 本地计算边沿检测
                                val prevLocalState = mqttLastLocalStates[i]
                                if (stateLocal == SensorState.HAS_WATER && prevLocalState == SensorState.NO_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 触发本地告警：检测到液体 (当前值: ${channelsMqtt[i].filteredValue}, 阈值: ${channelsMqtt[i].threshold})"
                                    logManager.writeLog(channel = uiChannelNum, level = "WARN", message = msg, imagePath = "")
                                    showAlarmNotification("传感器 $uiChannelNum 本地报警", msg)
                                    triggerAlarm(applicationContext, uiChannelNum, isLocal = true)
                                    addConsoleLog("MQTT本地告警: $msg")
                                } else if (stateLocal == SensorState.NO_WATER && prevLocalState == SensorState.HAS_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 本地恢复正常：液体消失"
                                    logManager.writeLog(channel = uiChannelNum, level = "INFO", message = msg, imagePath = "")
                                    activeLocalAlarms.remove(uiChannelNum)
                                    addConsoleLog("MQTT本地恢复: $msg")
                                }
                                mqttLastLocalStates[i] = stateLocal

                                // 远端上报边沿检测
                                val prevRemoteState = mqttLastRemoteStates[i]
                                if (currentRemoteState == SensorState.HAS_WATER && prevRemoteState == SensorState.NO_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 触发远端告警：检测到液体"
                                    logManager.writeLog(channel = uiChannelNum, level = "WARN", message = msg, imagePath = "")
                                    showAlarmNotification("传感器 $uiChannelNum 远端报警", msg)
                                    triggerAlarm(applicationContext, uiChannelNum, isLocal = false)
                                    addConsoleLog("MQTT远端告警: $msg")
                                } else if (currentRemoteState == SensorState.NO_WATER && prevRemoteState == SensorState.HAS_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 远端恢复正常：液体消失"
                                    logManager.writeLog(channel = uiChannelNum, level = "INFO", message = msg, imagePath = "")
                                    activeRemoteAlarms.remove(uiChannelNum)
                                    addConsoleLog("MQTT远端恢复: $msg")
                                }
                                mqttLastRemoteStates[i] = currentRemoteState

                                // 若本地和远端均已恢复，从活跃告警中移除该通道
                                if (stateLocal == SensorState.NO_WATER && currentRemoteState == SensorState.NO_WATER
                                    && activeAlarms.contains(uiChannelNum)) {
                                    activeAlarms.remove(uiChannelNum)
                                    _activeAlarmsState.value = activeAlarms.toSet()
                                }
                            }
                            // 异步保存到本地
                            serviceScope.launch(Dispatchers.IO) {
                                val currentPoints = Array(3) { debugDataPoints[it].value }
                                com.water.von.utils.SensorDataPersistence.saveDataPoints(applicationContext, "sensor_debug_mqtt.json", currentPoints)
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
                        logManager.writeLog(
                            channel = 1,
                            level = "INFO",
                            message = "接收到污水图片快照",
                            imagePath = relativePath
                        )
                    }

                    // ── 突变检测（对应 ESP32 mutationDetector.processFrame）────────
                    val sp = getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                    if (sp.getBoolean("mut_enable", false)) {
                        val blockThresh  = sp.getInt("mut_block_thresh_pct", 15) / 100f
                        val minBlocks    = sp.getInt("mut_min_blocks", 3)
                        val alarm = mutationDetector.processFrame(
                            jpegBytes   = payloadBytes,
                            blockThresh = blockThresh,
                            minBlocks   = minBlocks
                        )
                        addConsoleLog(
                            "[突变检测] Y=${String.format("%.1f", mutationDetector.lastYGlobal)}" +
                            " C_changed=${mutationDetector.lastCChanged}/${MutationDetector.MD_GRID_COUNT}" +
                            " thresh=${String.format("%.2f", blockThresh)}" +
                            " alarm=${if (alarm) "YES" else "NO"}"
                        )
                        if (alarm) {
                            val msg = "摄像头检测到有物体进入视野（变化网格 ${mutationDetector.lastCChanged}/${MutationDetector.MD_GRID_COUNT}）"
                            showAlarmNotification("📷 摄像头入侵报警", msg)
                            speakAlert(applicationContext, "摄像头检测到有物体进入视野")
                            logManager.writeLog(channel = 1, level = "WARN", message = msg, imagePath = relativePath)
                        }
                    }
                }
            }

            topic == MqttTopics.SYSTEM_STATE -> {
                try {
                    val payloadStr = String(payloadBytes, Charsets.UTF_8)
                    val json = org.json.JSONObject(payloadStr)
                    val sensorId = json.optInt("sensorId", 1)
                    val stage = json.optInt("stage", -1)
                    val remark = json.optString("remark", "")
                    val duration = json.optDouble("duration", 0.0).toFloat()
                    val pumpTime = json.optInt("pumpTime", 0)
                    val uptime = json.optLong("uptime", 0L)
                    val stageStartSec = json.optLong("stageStartSec", 0L)

                    if (stage != -1) {
                        // 计算 elapsed 已流逝秒数（防负校准）
                        val elapsedRaw = (uptime - stageStartSec).toInt()
                        val elapsed = if (elapsedRaw < 0) 0 else elapsedRaw

                        // 1. 本地根据 stage 强制注入有水/无水关键字以对齐 UI 端的模糊匹配机制
                        val hasWater = stage in 2..9
                        val waterStateText = if (hasWater) "有水" else "无水"
                        val msgText = "通道${sensorId}检测到${waterStateText}，状态跳转：${remark}"
                        
                        // 确定事件日志级别
                        val level = when (stage) {
                            9 -> "INFO"
                            else -> "INFO"
                        }

                        // 2. 归档本地事件日志
                        val logEntry = logManager.writeLog(
                            channel = sensorId,
                            level = level,
                            message = msgText,
                            imagePath = ""
                        )

                        // 3. 实时推送给 UI 的订阅 Flow
                        updateChannelFlow(sensorId, logEntry)

                        // 4. 更新高能状态流与指示灯状态
                        _stateUpdateFlow.value = StateUpdateEvent(
                            sensorId = sensorId,
                            stage = stage,
                            duration = duration,
                            pumpTime = pumpTime,
                            elapsed = elapsed
                        )
                        _systemStatus.value = stage.toString()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析 SYSTEM_STATE JSON 失败: ${e.message}")
                }
            }
            topic == "${MqttTopics.PREFIX}/alarm" -> {
                // Retained 告警主题处理：断线期间的告警在重连成功后由 Broker 自动表2
                // payload 为空表示 ESP32 已主动清除（告警已解除），对空消息不做任何处理
                val payload = String(payloadBytes, Charsets.UTF_8).trim()
                if (payload.isNotEmpty()) {
                    // Retained 告警补发：捨出高优先级通知，确保断线期间的告警不被遗漏
                    addConsoleLog("⭐ Retained 告警被动补发收到: $payload")
                    showAlarmNotification("📡 断线期间告警补发", "重连后检测到未处理的告警: $payload")
                } else {
                    // 收到空 payload：ESP32 已主动清除告警保留状态，告警已解除
                    addConsoleLog("Retained 告警已由 ESP32 清除（告警状态已恢复）")
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

        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_sensor_debug", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
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
    // BLE 模式复用 companion 中的 channels[] (SensorChannel) 进行滤波+触发判断
    // 记录上一次的触发状态用于边沿检测（日志告警/恢复记录）

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

        // 2. 初始化触发状态与 SensorChannel
        bleLastSeqNum = -1
        for (i in 0 until 3) {
            bleLastLocalStates[i] = SensorState.NO_WATER
            bleLastRemoteStates[i] = SensorState.NO_WATER
            channelsBle[i].reset()
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
                        val sensor1 = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                        val sensor2 = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                        val sensor3 = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                        
                        val stateByte = data[6].toInt() and 0xFF
                        val seqNum = data[7].toInt() and 0xFF
                        if (seqNum != bleLastSeqNum) {
                            bleLastSeqNum = seqNum
                            
                            // 重置 15 分钟无数据超时定时器
                            resetBleTimeoutTimer()

                            // 更新全局 Flow 让 UI 实时消费
                            _latestSensorRawData.value = intArrayOf(sensor1, sensor2, sensor3, stateByte)

                            _debugPacketCountBle.value++

                            // 后台逻辑处理：使用 BLE 专属通道处理器进行滤波与触发状态机计算
                            val physicalChannels = arrayOf(sensor1, sensor2, sensor3)
                            for (i in 0 until 3) {
                                val rawValue = physicalChannels[i]
                                val stateLocal = channelsBle[i].pushRaw(rawValue)
                                val hasWaterLocal = stateLocal == SensorState.HAS_WATER
                                val hasWaterRemote = (stateByte and (1 shl i)) != 0
                                
                                val newPoint = SensorDataPoint(
                                    ch0 = rawValue,
                                    ch1 = channelsBle[i].filteredValue,
                                    ch2 = channelsBle[i].baseline,
                                    ch3 = channelsBle[i].threshold,
                                    hasWater = hasWaterLocal,
                                    hasWaterRemote = hasWaterRemote
                                )
                                
                                debugDataPointsBle[i].update { current ->
                                    val list = if (current.size >= 1020) current.drop(1) else current
                                    list + newPoint
                                }

                                val currentRemoteState = if (hasWaterRemote) SensorState.HAS_WATER else SensorState.NO_WATER
                                val uiChannelNum = i + 1

                                // 本地计算边沿检测
                                val prevLocalState = bleLastLocalStates[i]
                                if (stateLocal == SensorState.HAS_WATER && prevLocalState == SensorState.NO_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 触发本地告警：检测到液体 (当前值: ${channelsBle[i].filteredValue}, 阈值: ${channelsBle[i].threshold})"
                                    logManager.writeLog(channel = uiChannelNum, level = "WARN", message = msg, imagePath = "")
                                    showAlarmNotification("传感器 $uiChannelNum 本地报警", msg)
                                    triggerAlarm(applicationContext, uiChannelNum, isLocal = true)
                                    addConsoleLog("BLE本地告警: $msg")
                                } else if (stateLocal == SensorState.NO_WATER && prevLocalState == SensorState.HAS_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 本地恢复正常：液体消失"
                                    logManager.writeLog(channel = uiChannelNum, level = "INFO", message = msg, imagePath = "")
                                    activeLocalAlarms.remove(uiChannelNum)
                                    addConsoleLog("BLE本地恢复: $msg")
                                }
                                bleLastLocalStates[i] = stateLocal

                                // 远端上报边沿检测
                                val prevRemoteState = bleLastRemoteStates[i]
                                if (currentRemoteState == SensorState.HAS_WATER && prevRemoteState == SensorState.NO_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 触发远端告警：检测到液体"
                                    logManager.writeLog(channel = uiChannelNum, level = "WARN", message = msg, imagePath = "")
                                    showAlarmNotification("传感器 $uiChannelNum 远端报警", msg)
                                    triggerAlarm(applicationContext, uiChannelNum, isLocal = false)
                                    addConsoleLog("BLE远端告警: $msg")
                                } else if (currentRemoteState == SensorState.NO_WATER && prevRemoteState == SensorState.HAS_WATER) {
                                    val msg = "传感器 Sensor$uiChannelNum 远端恢复正常：液体消失 (当前值: ${channelsBle[i].filteredValue}, 阈值: ${channelsBle[i].threshold})"
                                    logManager.writeLog(channel = uiChannelNum, level = "INFO", message = msg, imagePath = "")
                                    activeRemoteAlarms.remove(uiChannelNum)
                                    addConsoleLog("BLE远端恢复: $msg")
                                }
                                bleLastRemoteStates[i] = currentRemoteState

                                // 若本地和远端均已恢复，从活跃告警中移除该通道
                                if (stateLocal == SensorState.NO_WATER && currentRemoteState == SensorState.NO_WATER
                                    && activeAlarms.contains(uiChannelNum)) {
                                    activeAlarms.remove(uiChannelNum)
                                    _activeAlarmsState.value = activeAlarms.toSet()
                                }
                            }
                            // 异步保存到本地
                            serviceScope.launch(Dispatchers.IO) {
                                val currentPoints = Array(3) { debugDataPointsBle[it].value }
                                com.water.von.utils.SensorDataPersistence.saveDataPoints(applicationContext, "sensor_debug_ble.json", currentPoints)
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
        periodicPhotoJob?.cancel()  // 停止定时拍照任务
        periodicPhotoJob = null
        cancelHeartbeat()         // 取消 AlarmManager 心跳
        releaseMqttLocks()        // 释放 MQTT WakeLock + WifiLock

        // 释放 TTS 引擎资源
        shutdownTts()

        // 清理调试状态标记，防止 Service 重建后残留
        _isMqttDebuggingActive.value = false
        activeSensorPrefix = null
        cancelAutoStopTimer()
        shouldReconnect = false
        reconnectJob?.cancel()
        disconnectMqtt()
        serviceJob.cancel()
        companionJob.cancel()
        isServiceRunning = false
        addConsoleLog("MqttService 已销毁")
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 定时巡视拍照 & 突变检测辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 从 SharedPreferences 读取配置，若启用则启动定时拍照协程
     * 对应 ESP32 端：get_mutation_enable() + get_mutation_interval_sec()
     */
    private fun startPeriodicPhotoTaskIfEnabled() {
        val sp = getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        if (!sp.getBoolean("mut_enable", false)) {
            addConsoleLog("定时巡视拍照：未启用，跳过")
            return
        }
        val intervalSec = sp.getInt("mut_interval_sec", 10).toLong().coerceIn(5, 300)
        addConsoleLog("定时巡视拍照已启动（间隔 ${intervalSec}s，对应 ESP32 mut_interval）")

        periodicPhotoJob = serviceScope.launch {
            // 首次延迟一个完整周期，避免启动瞬间连发指令
            kotlinx.coroutines.delay(intervalSec * 1000L)
            while (isActive) {
                if (_isConnected.value) {
                    val jsonPayload = JSONObject().apply {
                        put("site_name", _stationEnglishName.value)
                        put("action", "capture")
                    }.toString()
                    publishInternal(
                        topic   = MqttTopics.CONTROL_TAKE_PHOTO,
                        payload = jsonPayload
                    )
                    addConsoleLog("[定时巡视] 已下发拍照指令 → ${_stationEnglishName.value}")
                } else {
                    addConsoleLog("[定时巡视] MQTT 未连接，跳过本次拍照")
                }
                kotlinx.coroutines.delay(intervalSec * 1000L)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Doze 心跳与锁管理辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 获取 MQTT 常驻 WakeLock + WifiLock。
     * WakeLock: 保持 CPU 微弱运转（不亮屏），确保休眠期收到 MQTT 数据包时 CPU 处于工作状态。
     * WifiLock: 高性能模式防止 Wi-Fi 休眠（对 4G/5G 无效，但对 Wi-Fi 环境有显著改善）。
     */
    private fun acquireMqttLocks() {
        try {
            if (mqttWakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                mqttWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "von:MqttWakeLock"
                )
                mqttWakeLock?.acquire()  // 常驻持有，在 onDestroy 释放
                addConsoleLog("已获取 MQTT PARTIAL_WAKE_LOCK")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }
        try {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "von:MqttWifiLock"
                )
                wifiLock?.acquire()
                addConsoleLog("已获取 MQTT WifiLock (FULL_HIGH_PERF)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 WifiLock 失败: ${e.message}")
        }
    }

    /**
     * 释放 MQTT WakeLock + WifiLock。在 onDestroy 中调用。
     */
    private fun releaseMqttLocks() {
        try { if (mqttWakeLock?.isHeld == true) mqttWakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        mqttWakeLock = null
        wifiLock = null
        addConsoleLog("已释放 MQTT WakeLock + WifiLock")
    }

    /**
     * 调度下一次 Doze 兼容心跳。
     * 使用 setExactAndAllowWhileIdle()：即使处于深层打盹（Deep Doze）也能按时触发。
     * Android 9+ 要求相邻两次间隔 ≥ 9 分钟，系统会自动顺延到满足条件。
     */
    private fun scheduleNextHeartbeat() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                this,
                1001,
                android.content.Intent(this, com.water.von.receiver.MqttHeartbeatReceiver::class.java).apply {
                    action = com.water.von.receiver.MqttHeartbeatReceiver.ACTION_MQTT_HEARTBEAT
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            am.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS,
                pi
            )
            addConsoleLog("[Doze心跳] 已调度下一次心跳（${HEARTBEAT_INTERVAL_MS / 60000} 分钟后）")
        } catch (e: Exception) {
            Log.e(TAG, "调度 AlarmManager 心跳失败: ${e.message}")
        }
    }

    /**
     * 取消所有尚未触发的心跳 Alarm。在 onDestroy 中调用。
     */
    private fun cancelHeartbeat() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                this,
                1001,
                android.content.Intent(this, com.water.von.receiver.MqttHeartbeatReceiver::class.java).apply {
                    action = com.water.von.receiver.MqttHeartbeatReceiver.ACTION_MQTT_HEARTBEAT
                },
                android.app.PendingIntent.FLAG_NO_CREATE or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            pi?.let { am.cancel(it) }
            addConsoleLog("[Doze心跳] 已取消心跳 Alarm")
        } catch (e: Exception) {
            Log.e(TAG, "取消 AlarmManager 心跳失败: ${e.message}")
        }
    }
}
