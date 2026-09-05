package com.water.von.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.water.von.service.MqttService

/**
 * AlarmManager Doze 兼容心跳广播接收器
 *
 * 由 AlarmManager.setExactAndAllowWhileIdle() 触发。
 * 即使系统处于深层打盹（Deep Doze）状态，此 Receiver 也会在系统分配的维护窗口内
 * 被短暂唤醒，然后通知 MqttService 执行一次心跳/重连检查。
 *
 * 工作流程：
 *   AlarmManager 触发 → onReceive → startForegroundService → MqttService.onStartCommand
 *   → 检查 MQTT 连接 → 若断开则重连 → 调度下一次 Alarm
 */
class MqttHeartbeatReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MQTT_HEARTBEAT = "com.water.von.ACTION_MQTT_HEARTBEAT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MQTT_HEARTBEAT) return

        // 通知 MqttService 执行心跳检查，使用 startForegroundService 确保在后台也能启动
        val serviceIntent = Intent(context, MqttService::class.java).apply {
            action = MqttService.ACTION_HEARTBEAT_CHECK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
