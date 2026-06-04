package com.water.von.data

import org.json.JSONObject

/**
 * 污水采样通道日志实体类
 * 对应每日 JSON Lines 格式日志文件中的每一行
 */
data class LogEntry(
    val time: String,          // 时间戳，格式为 HH:mm:ss
    val channel: Int,          // 通道编号 (1, 2, 3)
    val level: String,         // 日志级别 (INFO, WARN, ERROR)
    val message: String,       // 日志内容
    val imagePath: String = "" // 关联的本地图片相对路径，若无则为空字符串
) {
    /**
     * 将 LogEntry 转化为 JSON 字符串（单行）
     */
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("time", time)
        json.put("channel", channel)
        json.put("level", level)
        json.put("message", message)
        json.put("image_path", imagePath)
        return json.toString()
    }

    companion object {
        /**
         * 从 JSON 字符串解析为 LogEntry
         */
        fun fromJsonString(jsonStr: String): LogEntry? {
            return try {
                val json = JSONObject(jsonStr)
                LogEntry(
                    time = json.optString("time", ""),
                    channel = json.optInt("channel", 1),
                    level = json.optString("level", "INFO"),
                    message = json.optString("message", ""),
                    imagePath = json.optString("image_path", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
