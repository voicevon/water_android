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
    private val sharedPrefs = com.water.von.utils.SecurePrefs.get(application)
    
    val brokerUrlFlow = MutableStateFlow(getBrokerUrl())

    fun refreshBrokerUrl() {
        brokerUrlFlow.value = getBrokerUrl()
    }

    private fun getBrokerUrl(): String {
        val ip = sharedPrefs.getString("broker_ip", "voicevon.vicp.io") ?: "voicevon.vicp.io"
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

    // 倒计时与双进度条相关的 Flow
    val currentSensorId = MutableStateFlow(-1)
    val samplingDuration = MutableStateFlow(0f)
    val pumpTimePreset = MutableStateFlow(0)
    val pumpWorkTimeMax = MutableStateFlow(0)
    val pumpWorkTimeRemaining = MutableStateFlow(0)
    val restTimeMax = MutableStateFlow(0)
    val restTimeRemaining = MutableStateFlow(0)
    val totalSamplingTimeMax = MutableStateFlow(0)
    val totalSamplingTimeRemaining = MutableStateFlow(0)

    private var pumpCountdownJob: kotlinx.coroutines.Job? = null
    private var restCountdownJob: kotlinx.coroutines.Job? = null
    private var totalCountdownJob: kotlinx.coroutines.Job? = null

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
                    hasWater(msg)?.let { pipe1HasWater.value = it }
                    updateStatusFromMessage(msg)
                }
            }
        }
        viewModelScope.launch {
            MqttService.latestLogChannel2.collect { entry ->
                entry?.message?.let { msg ->
                    hasWater(msg)?.let { pipe2HasWater.value = it }
                    updateStatusFromMessage(msg)
                }
            }
        }
        viewModelScope.launch {
            MqttService.latestLogChannel3.collect { entry ->
                entry?.message?.let { msg ->
                    hasWater(msg)?.let { pipe3HasWater.value = it }
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
        viewModelScope.launch {
            MqttService.stateUpdateFlow.collect { event ->
                if (event != null) {
                    currentSensorId.value = event.sensorId
                    samplingDuration.value = event.duration
                    pumpTimePreset.value = event.pumpTime
                    startCountdown(event.stage, event.duration, event.pumpTime, event.elapsed)
                }
            }
        }
    }

    private fun hasWater(msg: String): Boolean? {
        val noWaterKeywords = listOf("泵停", "停止", "关闭", "无水", "排空", "结束", "失败", "未检测")
        if (noWaterKeywords.any { msg.contains(it) }) {
            return false
        }
        val hasWaterKeywords = listOf("泵启", "水泵启动", "启动", "有水", "抽水")
        if (hasWaterKeywords.any { msg.contains(it) }) {
            return true
        }
        return null
    }

    private fun updateStatusFromMessage(msg: String) {
        val index = matchStatusIndex(msg)
        if (index != -1) {
            currentStatusIndex.value = index
        }
    }

    private fun matchStatusIndex(status: String): Int {
        return when {
            status.contains("等待") || status.contains("waiting_1") || status.contains("idle") || status == "1" -> 0
            status.contains("准备") || status.contains("prepare") || status.contains("sampling_prepare") || status == "2" -> 1
            status.contains("头样") || status.contains("head_sampling") || status.contains("sampling_head") || status == "3" -> 2
            status.contains("waiting_2") || status == "4" -> 3
            status.contains("中样") || status.contains("mid_sampling") || status.contains("sampling_mid") || status == "5" -> 4
            status.contains("waiting_3") || status == "6" -> 5
            status.contains("尾样") || status.contains("tail_sampling") || status.contains("sampling_tail") || status == "7" -> 6
            status.contains("waiting_4") || status == "8" -> 7
            status.contains("排空") || status.contains("drain") || status.contains("draining") || status == "9" -> 8
            status.contains("结束") || status.contains("finish") || status.contains("finished") || status == "10" -> 9
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

        // 发送控制指令至 MQTT 主题，载荷为当前目标站点的英文名
        MqttService.publish(context, MqttTopics.CONTROL_TAKE_PHOTO, MqttService.stationEnglishName.value)

        // 启动 15 秒超时强制解锁，防止因丢包或树莓派离线导致按钮永久卡死
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(15000L)
            if (isTakingPhoto.value) {
                isTakingPhoto.value = false
            }
        }
    }

    private fun startCountdown(stage: Int, duration: Float, pumpTime: Int, elapsed: Int) {
        pumpCountdownJob?.cancel()
        restCountdownJob?.cancel()
        totalCountdownJob?.cancel()

        pumpWorkTimeMax.value = 0
        pumpWorkTimeRemaining.value = 0
        restTimeMax.value = 0
        restTimeRemaining.value = 0
        totalSamplingTimeMax.value = 0
        totalSamplingTimeRemaining.value = 0

        // 1. 水泵运行计时器 (Stage 3, 5, 7, 9)
        if (stage == 3 || stage == 5 || stage == 7 || stage == 9) {
            pumpWorkTimeMax.value = pumpTime
            val remaining = pumpTime - elapsed
            if (remaining > 0) {
                pumpCountdownJob = viewModelScope.launch {
                    var rem = remaining
                    while (rem >= 0) {
                        pumpWorkTimeRemaining.value = rem
                        kotlinx.coroutines.delay(1000L)
                        rem--
                    }
                }
            }
        }

        // 2. 待命/等待倒计时器 (Stage 2, 4, 6, 8, 10)
        if (stage == 2 || stage == 4 || stage == 6 || stage == 8 || stage == 10) {
            val restMax = when (stage) {
                2 -> 180
                8 -> 180
                10 -> 600
                else -> {
                    // Stage 4 & 6 延时待命：动态估算休眠时间
                    val calculated = ((duration * 60f * 0.75f - 180f) - pumpTime * 3f) / 2f
                    if (calculated < 0f) 0 else calculated.toInt()
                }
            }

            restTimeMax.value = restMax
            var rem = restMax - elapsed

            restCountdownJob = viewModelScope.launch {
                while (true) {
                    restTimeRemaining.value = rem
                    kotlinx.coroutines.delay(1000L)
                    rem--
                    // 非 Stage 8 和 Stage 10 倒数减为负数即退出
                    if (stage != 8 && stage != 10 && rem < 0) {
                        break
                    }
                }
            }
        }

        // 3. 计算并启动总采样进度倒计时 (Stage 2 到 9)
        if (stage in 2..9) {
            val restTime = (((duration * 60f * 0.75f - 180f) - pumpTime * 3f) / 2f).toInt().coerceAtLeast(0)
            val totalActive = 180 + pumpTime * 4 + restTime * 2 + 180
            totalSamplingTimeMax.value = totalActive

            val initialRem = when (stage) {
                2 -> (180 - elapsed) + pumpTime * 4 + restTime * 2 + 180
                3 -> (pumpTime - elapsed) + pumpTime * 3 + restTime * 2 + 180
                4 -> (restTime - elapsed) + pumpTime * 3 + restTime * 1 + 180
                5 -> (pumpTime - elapsed) + pumpTime * 2 + restTime * 1 + 180
                6 -> (restTime - elapsed) + pumpTime * 2 + 180
                7 -> (pumpTime - elapsed) + pumpTime * 1 + 180
                8 -> (180 - elapsed) + pumpTime
                9 -> (pumpTime - elapsed)
                else -> 0
            }.coerceAtLeast(0)

            totalCountdownJob = viewModelScope.launch {
                var rem = initialRem
                while (rem >= 0) {
                    totalSamplingTimeRemaining.value = rem
                    kotlinx.coroutines.delay(1000L)
                    rem--
                }
            }
        }
    }
}
