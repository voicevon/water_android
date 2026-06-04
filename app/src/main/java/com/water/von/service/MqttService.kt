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
import com.water.von.data.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    @Volatile
    private var shouldReconnect = true
    private var reconnectDelay = 2000L // 初始重连延时 2 秒

    companion object {
        // 使用 Flow 向前台 UI 实时暴露通信状态
        val isConnected = MutableStateFlow(false)
        val systemStatus = MutableStateFlow("offline")
        val systemInfo = MutableStateFlow("未接收到数据\n---\n---")
        val latestLogChannel1 = MutableStateFlow<LogEntry?>(null)
        val latestLogChannel2 = MutableStateFlow<LogEntry?>(null)
        val latestLogChannel3 = MutableStateFlow<LogEntry?>(null)
        val latestPhotoPath = MutableStateFlow<String?>(null)
        val mqttLogs = MutableStateFlow<List<String>>(emptyList())

        // 单例引用，用于从 UI 触发 MQTT 发布指令
        @Volatile
        private var instance: MqttService? = null

        /**
         * 向指定主题发布消息
         */
        fun publish(topic: String, payload: String): Boolean {
            val service = instance
            if (service != null && service.mqttClient?.isConnected == true) {
                return try {
                    val message = MqttMessage(payload.toByteArray(Charsets.UTF_8))
                    message.qos = 1
                    service.mqttClient?.publish(topic, message)
                    service.addConsoleLog("发布消息成功 -> 主题: $topic, 内容: $payload")
                    true
                } catch (e: Exception) {
                    service.addConsoleLog("发布消息失败: ${e.message}")
                    false
                }
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        logManager = LogManager(applicationContext)
        createNotificationChannels()
        addConsoleLog("MqttService 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            isServiceRunning = true
            startForegroundService()
            shouldReconnect = true
            connectMqtt()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shouldReconnect = false
        disconnectMqtt()
        serviceJob.cancel()
        isServiceRunning = false
        instance = null
        addConsoleLog("MqttService 已销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * 添加控制台网络调试日志
     */
    private fun addConsoleLog(message: String) {
        Log.i(TAG, message)
        val currentLogs = mqttLogs.value.toMutableList()
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        currentLogs.add(0, "[$timestamp] $message")
        if (currentLogs.size > 100) { // 最多保留100条调试日志
            currentLogs.removeAt(currentLogs.size - 1)
        }
        mqttLogs.value = currentLogs
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
     * 连接 MQTT 代理服务器
     */
    private fun connectMqtt() {
        serviceScope.launch {
            val sharedPreferences = getSharedPreferences("mqtt_config", Context.MODE_PRIVATE)
            val brokerIp = sharedPreferences.getString("broker_ip", "voicevon.vicp.io") ?: "voicevon.vicp.io"
            val brokerPort = sharedPreferences.getInt("broker_port", 1883)
            val username = sharedPreferences.getString("username", "von") ?: "von"
            val password = sharedPreferences.getString("password", "von123456") ?: "von123456"
            val customClientId = sharedPreferences.getString("client_id", "") ?: ""
            
            val brokerUrl = "tcp://$brokerIp:$brokerPort"
            val clientId = if (customClientId.isNotEmpty()) customClientId else "WaterAndroid_" + UUID.randomUUID().toString().substring(0, 8)

            addConsoleLog("尝试连接至 MQTT Broker: $brokerUrl...")
            try {
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
                        isConnected.value = true
                        reconnectDelay = 2000L // 重置重连延迟
                        addConsoleLog("MQTT 连接成功: $serverURI")
                        subscribeToTopics()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        isConnected.value = false
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
                    }
                })

                mqttClient?.connect(options)

            } catch (e: Exception) {
                addConsoleLog("MQTT 连接失败异常: ${e.message}")
                isConnected.value = false
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
            mqttClient?.subscribe("pi_water/system/status", 1)
            mqttClient?.subscribe("pi_water/system/info", 1)
            mqttClient?.subscribe("pi_water/photo", 1)
            mqttClient?.subscribe("pi_water/log/+", 1) // 通配符订阅 1, 2, 3 通道日志
            addConsoleLog("已成功订阅监控主题队列")
        } catch (e: Exception) {
            addConsoleLog("订阅主题失败: ${e.message}")
        }
    }

    /**
     * 触发指数避退延迟重连
     */
    private fun triggerReconnection() {
        serviceScope.launch {
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
            topic == "pi_water/system/status" -> {
                val status = String(payloadBytes, Charsets.UTF_8)
                systemStatus.value = status
            }
            
            topic == "pi_water/system/info" -> {
                val info = String(payloadBytes, Charsets.UTF_8)
                systemInfo.value = info
            }

            topic == "pi_water/photo" -> {
                serviceScope.launch {
                    val relativePath = logManager.savePhoto(payloadBytes)
                    if (relativePath.isNotEmpty()) {
                        latestPhotoPath.value = relativePath
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

            topic.startsWith("pi_water/log/") -> {
                val channelStr = topic.substringAfter("pi_water/log/")
                val channelId = channelStr.toIntOrNull() ?: 1
                val logPayload = String(payloadBytes, Charsets.UTF_8)
                
                // pi_water 日志消息通常由 \n 拆分为三行。我们提取第三行的内容作为具体动作消息
                val lines = logPayload.split("\n")
                val actionMessage = if (lines.size >= 3) lines[2] else logPayload
                
                val level = if (actionMessage.contains("ERROR") || actionMessage.contains("异常") || actionMessage.contains("失踪")) {
                    "ERROR"
                } else if (actionMessage.contains("警告") || actionMessage.contains("丟")) {
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
            1 -> latestLogChannel1.value = logEntry
            2 -> latestLogChannel2.value = logEntry
            3 -> latestLogChannel3.value = logEntry
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
        manager.notify(System.currentTimeMillis().toInt(), alarmNotification)
    }

    private fun disconnectMqtt() {
        try {
            if (mqttClient?.isConnected == true) {
                mqttClient?.disconnect()
            }
            mqttClient?.close()
            mqttClient = null
            isConnected.value = false
            addConsoleLog("已断开 MQTT 连接")
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败: ${e.message}")
        }
    }
}
