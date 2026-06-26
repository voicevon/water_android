package com.water.von.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * MQTT 内存单例指令总线
 * 负责 ViewModel 与 MqttService 之间的解耦指令传输
 * 替代原有的 context.startForegroundService(Intent) 传参机制
 */
sealed class MqttCommand {
    data class Publish(val topic: String, val payload: String) : MqttCommand()
    data class Subscribe(val topic: String) : MqttCommand()
    data class Unsubscribe(val topic: String) : MqttCommand()
    object Reconnect : MqttCommand()
}

object MqttBus {
    // 设置缓冲容量避免协程挂起或丢包
    private val _commands = MutableSharedFlow<MqttCommand>(extraBufferCapacity = 128)
    val commands: SharedFlow<MqttCommand> = _commands.asSharedFlow()

    fun sendCommand(command: MqttCommand) {
        _commands.tryEmit(command)
    }
}
