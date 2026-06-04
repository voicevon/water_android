package com.water.von.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.water.von.service.MqttService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
}
