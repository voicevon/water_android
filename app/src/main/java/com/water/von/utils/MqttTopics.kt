package com.water.von.utils

import com.water.von.service.MqttService

/**
 * MQTT 主题集中管理
 * PREFIX 动态读取 MqttService.stationEnglishName，支持多站点切换
 * 全局不变的主题（如 sensor/photo）仍使用 const val
 */
object MqttTopics {
    /** 动态前缀：water/{stationName}，默认 water/dongzhan */
    val PREFIX: String
        get() = "water/${MqttService.stationEnglishName.value}"

    // Subscribed Topics（动态，随站点名变化）
    val SYSTEM_STATUS: String get() = "$PREFIX/system/status"
    val SYSTEM_INFO: String get() = "$PREFIX/system/info"
    val LOG_WILDCARD: String get() = "$PREFIX/log/+"
    val LOG_PREFIX: String get() = "$PREFIX/log/"

    // Subscribed Topics（全局固定，不随站点变化）
    const val PHOTO_WILDCARD = "water/photo/status/+"
    const val SENSOR_STATUS_TOPIC = "water/sensor/status"

    // Published Topics（全局固定）
    const val CONTROL_TAKE_PHOTO = "water/photo/take"
    const val SENSOR_CONTROL_TOPIC = "water/sensor/start"

    fun getDurationTopic(channelId: Int): String = "$PREFIX/config/duration/$channelId"
    fun getPumpTimeTopic(channelId: Int): String = "$PREFIX/config/pump_time/$channelId"
}
