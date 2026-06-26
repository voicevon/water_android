package com.water.von.utils

object MqttTopics {
    private const val PREFIX = "water/dongzhan"
    
    // Subscribed Topics
    const val SYSTEM_STATUS = "$PREFIX/system/status"
    const val SYSTEM_INFO = "$PREFIX/system/info"
    const val PHOTO = "$PREFIX/camera/status"
    const val LOG_WILDCARD = "$PREFIX/log/+"
    const val SENSOR_STATUS_TOPIC = "water/sensor/status"
    
    // Published Topics
    const val CONTROL_TAKE_PHOTO = "$PREFIX/camera/cmd"
    const val SENSOR_CONTROL_TOPIC = "water/sensor/start"
    
    // Prefix for logs topic matching
    const val LOG_PREFIX = "$PREFIX/log/"

    fun getDurationTopic(channelId: Int): String = "$PREFIX/config/duration/$channelId"
    fun getPumpTimeTopic(channelId: Int): String = "$PREFIX/config/pump_time/$channelId"
}
