package com.water.von.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.water.von.service.MqttService
import kotlinx.coroutines.flow.StateFlow

/**
 * 主监控页面 ViewModel
 * 绑定 MqttService 的实时数据流并暴露给 Compose UI
 */
class MonitorViewModel : ViewModel() {
    val isConnected: StateFlow<Boolean> = MqttService.isConnected
    val systemStatus: StateFlow<String> = MqttService.systemStatus
    val systemInfo: StateFlow<String> = MqttService.systemInfo
    val latestLogChannel1 = MqttService.latestLogChannel1
    val latestLogChannel2 = MqttService.latestLogChannel2
    val latestLogChannel3 = MqttService.latestLogChannel3
    val latestPhotoPath = MqttService.latestPhotoPath
}
