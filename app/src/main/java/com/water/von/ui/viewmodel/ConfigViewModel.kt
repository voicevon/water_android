package com.water.von.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import com.water.von.service.MqttService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MQTT 通信设置页面 ViewModel
 * 负责管理 MQTT 连接配置表单并触发前台服务重连
 */
class ConfigViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPrefs = com.water.von.utils.SecurePrefs.get(application)

    // 表单状态 Flow
    private val _brokerIp = MutableStateFlow("")
    val brokerIp: StateFlow<String> = _brokerIp

    private val _brokerPort = MutableStateFlow(1883)
    val brokerPort: StateFlow<Int> = _brokerPort

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password

    private val _clientId = MutableStateFlow("")
    val clientId: StateFlow<String> = _clientId

    // 绑定网络诊断调试日志与网络状态
    val isConnected: StateFlow<Boolean> = MqttService.isConnected
    val consoleLogs: StateFlow<List<String>> = MqttService.mqttLogs

    init {
        loadConfig()
    }

    fun updateBrokerIp(value: String) { _brokerIp.value = value }
    fun updateBrokerPort(value: Int) { _brokerPort.value = value }
    fun updateUsername(value: String) { _username.value = value }
    fun updatePassword(value: String) { _password.value = value }
    fun updateClientId(value: String) { _clientId.value = value }

    /**
     * 从 SharedPreferences 中加载配置
     */
    fun loadConfig() {
        _brokerIp.value = sharedPrefs.getString("broker_ip", "voicevon.vicp.io") ?: "voicevon.vicp.io"
        _brokerPort.value = sharedPrefs.getInt("broker_port", 1883)
        _username.value = sharedPrefs.getString("username", "von") ?: "von"
        _password.value = sharedPrefs.getString("password", "von123456") ?: "von123456"
        _clientId.value = sharedPrefs.getString("client_id", "") ?: ""
    }

    /**
     * 保存当前表单配置到 SharedPreferences 并通知重连
     */
    fun saveAndConnect() {
        sharedPrefs.edit().apply {
            putString("broker_ip", _brokerIp.value)
            putInt("broker_port", _brokerPort.value)
            putString("username", _username.value)
            putString("password", _password.value)
            putString("client_id", _clientId.value)
            apply()
        }

        // 保证后台常驻服务启动并通知内部重连
        val context = getApplication<Application>()
        val serviceIntent = Intent(context, MqttService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        com.water.von.service.MqttBus.sendCommand(com.water.von.service.MqttCommand.Reconnect)
    }
}
