package com.water.von.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.water.von.service.MqttService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 时间与水泵参数设置页面 ViewModel
 * 负责各通道参数滑块调节、本地存储与远端 MQTT 下发
 */
class ParametersViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPrefs = application.getSharedPreferences("parameter_config", Context.MODE_PRIVATE)

    // 通道 1 参数
    private val _duration1 = MutableStateFlow(60f)
    val duration1: StateFlow<Float> = _duration1
    private val _pumpTime1 = MutableStateFlow(10f)
    val pumpTime1: StateFlow<Float> = _pumpTime1

    // 通道 2 参数
    private val _duration2 = MutableStateFlow(120f)
    val duration2: StateFlow<Float> = _duration2
    private val _pumpTime2 = MutableStateFlow(10f)
    val pumpTime2: StateFlow<Float> = _pumpTime2

    // 通道 3 参数
    private val _duration3 = MutableStateFlow(40f)
    val duration3: StateFlow<Float> = _duration3
    private val _pumpTime3 = MutableStateFlow(10f)
    val pumpTime3: StateFlow<Float> = _pumpTime3

    init {
        loadParameters()
    }

    // 更新内存状态方法
    fun updateDuration1(value: Float) { _duration1.value = value }
    fun updatePumpTime1(value: Float) { _pumpTime1.value = value }

    fun updateDuration2(value: Float) { _duration2.value = value }
    fun updatePumpTime2(value: Float) { _pumpTime2.value = value }

    fun updateDuration3(value: Float) { _duration3.value = value }
    fun updatePumpTime3(value: Float) { _pumpTime3.value = value }

    /**
     * 加载保存的本地参数
     */
    fun loadParameters() {
        _duration1.value = sharedPrefs.getFloat("duration_1", 60f)
        _pumpTime1.value = sharedPrefs.getFloat("pump_time_1", 10f)

        _duration2.value = sharedPrefs.getFloat("duration_2", 120f)
        _pumpTime2.value = sharedPrefs.getFloat("pump_time_2", 10f)

        _duration3.value = sharedPrefs.getFloat("duration_3", 40f)
        _pumpTime3.value = sharedPrefs.getFloat("pump_time_3", 10f)
    }

    /**
     * 将特定通道的参数同步保存并下发到 MQTT
     * @param channelId 通道ID (1, 2, 3)
     */
    fun syncChannelParameters(channelId: Int): Boolean {
        val duration: Float
        val pumpTime: Float

        when (channelId) {
            1 -> {
                duration = _duration1.value
                pumpTime = _pumpTime1.value
            }
            2 -> {
                duration = _duration2.value
                pumpTime = _pumpTime2.value
            }
            3 -> {
                duration = _duration3.value
                pumpTime = _pumpTime3.value
            }
            else -> return false
        }

        // 1. 本地存储持久化
        sharedPrefs.edit().apply {
            putFloat("duration_$channelId", duration)
            putFloat("pump_time_$channelId", pumpTime)
            apply()
        }

        // 2. 通过 MQTT 远端同步下发 ( expected_duration 和 pump_work_time )
        val durationTopic = "pi_water/config/duration/$channelId"
        val pumpTimeTopic = "pi_water/config/pump_time/$channelId"

        // 按照树莓派格式，下发时间字符串（分钟/秒）
        val isDurationSent = MqttService.publish(durationTopic, duration.toString())
        val isPumpTimeSent = MqttService.publish(pumpTimeTopic, pumpTime.toString())

        return isDurationSent && isPumpTimeSent
    }
}
