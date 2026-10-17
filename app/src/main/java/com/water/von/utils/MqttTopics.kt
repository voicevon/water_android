package com.water.von.utils

import com.water.von.service.MqttService

/**
 * MQTT 主题集中管理
 * PREFIX 动态读取 MqttService.stationEnglishName，支持多站点切换
 * 全局不变的主题（如 sensor/photo）仍使用 const val
 */
object MqttTopics {
    /** 动态前缀：water/{stationName}，默认 water/home */
    val PREFIX: String
        get() = "water/${MqttService.stationEnglishName.value}"

    // Subscribed Topics（动态，随站点名变化）
    val SYSTEM_STATUS: String get() = "$PREFIX/system/status"
    val SYSTEM_INFO: String get() = "$PREFIX/system/info"
    val SYSTEM_STATE: String get() = "$PREFIX/state"

    // Subscribed Topics（全局固定，不随站点变化）
    const val PHOTO_WILDCARD = "water/photo/status/+"
    const val SENSOR_STATUS_TOPIC = "water/sensor/status"
    const val SENSOR_CALIBRATION_STATUS_TOPIC = "water/sensor/calibration/status"

    // 控制平面主题（随站点名/设备名动态变化：water/{station}/control 及 response）
    val CONTROL_TOPIC: String get() = "$PREFIX/control"
    val CONTROL_RESPONSE_TOPIC: String get() = "$PREFIX/control/response"

    fun getControlTopic(targetStation: String): String = "water/$targetStation/control"
    fun getControlResponseTopic(targetStation: String): String = "water/$targetStation/control/response"

    // Published Topics（全局固定）
    const val CONTROL_TAKE_PHOTO = "water/photo/take"
    const val SENSOR_CONTROL_TOPIC = "water/sensor/start"
    const val SENSOR_CALIBRATION_TOPIC = "water/sensor/calibration"
}
