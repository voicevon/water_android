package com.water.von.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.water.von.service.MqttService
import com.water.von.utils.MqttTopics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 主监控页面 ViewModel
 * 绑定 MqttService 的实时数据流并暴露给 Compose UI
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPrefs = application.getSharedPreferences("mqtt_config", Context.MODE_PRIVATE)
    
    val brokerUrlFlow = MutableStateFlow(getBrokerUrl())

    fun refreshBrokerUrl() {
        brokerUrlFlow.value = getBrokerUrl()
    }

    private fun getBrokerUrl(): String {
        val ip = sharedPrefs.getString("broker_ip", "") ?: ""
        val port = sharedPrefs.getInt("broker_port", 1883)
        return if (ip.isEmpty()) "未配置 Broker" else "$ip:$port"
    }

    val isConnected: StateFlow<Boolean> = MqttService.isConnected
    val systemStatus: StateFlow<String> = MqttService.systemStatus
    val systemInfo: StateFlow<String> = MqttService.systemInfo
    val latestLogChannel1 = MqttService.latestLogChannel1
    val latestLogChannel2 = MqttService.latestLogChannel2
    val latestLogChannel3 = MqttService.latestLogChannel3
    val latestPhotoPath = MqttService.latestPhotoPath

    // 远程拍照 Loading 防抖状态
    val isTakingPhoto = MutableStateFlow(false)
    private var timeoutJob: kotlinx.coroutines.Job? = null

    // 管道有水状态
    val pipe1HasWater = MutableStateFlow(false)
    val pipe2HasWater = MutableStateFlow(false)
    val pipe3HasWater = MutableStateFlow(false)

    // 10 步流程的状态索引 (0 到 9, -1 表示未知/未开始)
    val currentStatusIndex = MutableStateFlow(-1)

    init {
        viewModelScope.launch {
            MqttService.latestPhotoPath.collect { path ->
                if (path != null) {
                    isTakingPhoto.value = false
                    timeoutJob?.cancel()
                }
            }
        }
        viewModelScope.launch {
            MqttService.latestLogChannel1.collect { entry ->
                entry?.message?.let { msg ->
                    pipe1HasWater.value = isWaterDetected(msg)
                    updateStatusFromMessage(msg)
                }
            }
        }
        viewModelScope.launch {
            MqttService.latestLogChannel2.collect { entry ->
                entry?.message?.let { msg ->
                    pipe2HasWater.value = isWaterDetected(msg)
                    updateStatusFromMessage(msg)
                }
            }
        }
        viewModelScope.launch {
            MqttService.latestLogChannel3.collect { entry ->
                entry?.message?.let { msg ->
                    pipe3HasWater.value = isWaterDetected(msg)
                    updateStatusFromMessage(msg)
                }
            }
        }
        viewModelScope.launch {
            MqttService.systemStatus.collect { status ->
                val index = matchStatusIndex(status)
                if (index != -1) {
                    currentStatusIndex.value = index
                }
            }
        }
    }

    private fun isWaterDetected(msg: String): Boolean {
        val hasWaterKeywords = listOf("泵启", "水泵启动", "启动", "有水", "抽水")
        val noWaterKeywords = listOf("泵停", "停止", "关闭", "无水", "排空", "结束")
        
        var isWater = false
        for (kw in hasWaterKeywords) {
            if (msg.contains(kw)) {
                isWater = true
                break
            }
        }
        for (kw in noWaterKeywords) {
            if (msg.contains(kw)) {
                isWater = false
                break
            }
        }
        return isWater
    }

    private fun updateStatusFromMessage(msg: String) {
        val index = matchStatusIndex(msg)
        if (index != -1) {
            currentStatusIndex.value = index
        }
    }

    private fun matchStatusIndex(status: String): Int {
        return when {
            status.contains("等待") || status.contains("waiting_1") || status.contains("idle") || status == "0" -> 0
            status.contains("准备") || status.contains("prepare") || status.contains("sampling_prepare") || status == "1" -> 1
            status.contains("头样") || status.contains("head_sampling") || status.contains("sampling_head") || status == "2" -> 2
            status.contains("waiting_2") || status == "3" -> 3
            status.contains("中样") || status.contains("mid_sampling") || status.contains("sampling_mid") || status == "4" -> 4
            status.contains("waiting_3") || status == "5" -> 5
            status.contains("尾样") || status.contains("tail_sampling") || status.contains("sampling_tail") || status == "6" -> 6
            status.contains("waiting_4") || status == "7" -> 7
            status.contains("排空") || status.contains("drain") || status.contains("draining") || status == "8" -> 8
            status.contains("结束") || status.contains("finish") || status.contains("finished") || status == "9" -> 9
            else -> -1
        }
    }

    fun setPipeHasWater(channel: Int, hasWater: Boolean) {
        when (channel) {
            1 -> pipe1HasWater.value = hasWater
            2 -> pipe2HasWater.value = hasWater
            3 -> pipe3HasWater.value = hasWater
        }
    }

    fun setCurrentStatusIndex(index: Int) {
        if (index in -1..9) {
            currentStatusIndex.value = index
        }
    }

    /**
     * 手动触发远程拍照功能
     */
    fun takePhoto(context: Context) {
        if (isTakingPhoto.value) return
        isTakingPhoto.value = true

        // 发送控制指令至 MQTT 主题
        MqttService.publish(context, MqttTopics.CONTROL_TAKE_PHOTO, "take")

        // 启动 15 秒超时强制解锁，防止因丢包或树莓派离线导致按钮永久卡死
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(15000L)
            if (isTakingPhoto.value) {
                isTakingPhoto.value = false
            }
        }
    }
}
