package com.water.von.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地日志与照片沙盒存储管理器
 * 负责日志分卷归档、读取以及照片持久化
 */
class LogManager(private val context: Context) {
    private val TAG = "LogManager"

    // 获取沙盒私有根路径：/sdcard/Android/data/com.water.von/files/
    private val baseDir: File? = context.getExternalFilesDir(null)
    private val logsDir = File(baseDir, "logs")
    private val imagesDir = File(baseDir, "images")

    init {
        // 确保目录存在
        if (!logsDir.exists()) logsDir.mkdirs()
        if (!imagesDir.exists()) imagesDir.mkdirs()
    }

    /**
     * 追加写入一条日志记录 (JSON Lines 格式)
     * @param channel 通道号 (1, 2, 3)
     * @param level 日志级别 (INFO, WARN, ERROR)
     * @param message 动作或错误日志内容
     * @param imagePath 关联的本地图片路径
     */
    @Synchronized
    fun writeLog(channel: Int, level: String, message: String, imagePath: String = ""): LogEntry {
        val now = Date()
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        
        val timeStr = timeFormatter.format(now)
        val dateStr = dateFormatter.format(now)
        
        val logEntry = LogEntry(
            time = timeStr,
            channel = channel,
            level = level,
            message = message,
            imagePath = imagePath
        )

        try {
            val logFile = File(logsDir, "log_$dateStr.json")
            FileOutputStream(logFile, true).use { fos ->
                val line = logEntry.toJsonString() + "\n"
                fos.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入日志失败: ${e.message}")
        }
        return logEntry
    }

    /**
     * 读取指定日期的日志记录
     * @param dateStr 格式为 yyyyMMdd，如 20260604
     */
    @Synchronized
    fun readLogs(dateStr: String): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        val logFile = File(logsDir, "log_$dateStr.json")
        if (!logFile.exists()) return list

        try {
            BufferedReader(FileReader(logFile)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    line?.trim()?.let {
                        if (it.isNotEmpty()) {
                            LogEntry.fromJsonString(it)?.let { entry ->
                                list.add(entry)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取日志文件失败: ${e.message}")
        }
        return list
    }

    /**
     * 保存从 MQTT 接收到的原始图片二进制流，并返回相对路径
     * @param imageBytes 图片二进制数据
     */
    @Synchronized
    fun savePhoto(imageBytes: ByteArray): String {
        val now = Date()
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timeStr = formatter.format(now)
        val fileName = "IMG_$timeStr.jpg"
        val photoFile = File(imagesDir, fileName)

        try {
            FileOutputStream(photoFile).use { fos ->
                fos.write(imageBytes)
            }
            Log.i(TAG, "照片保存成功: ${photoFile.absolutePath}")
            return "images/$fileName"
        } catch (e: Exception) {
            Log.e(TAG, "保存照片失败: ${e.message}")
        }
        return ""
    }

    /**
     * 根据相对路径获取图片的绝对 File 对象
     */
    fun getPhotoFile(relativeImagePath: String): File? {
        if (relativeImagePath.isEmpty()) return null
        val fileName = relativeImagePath.substringAfter("images/")
        val file = File(imagesDir, fileName)
        return if (file.exists()) file else null
    }

    /**
     * 自动清理 30 天之前的过期日志与图片文件
     * @param retentionDays 保留天数 (默认 30 天)
     */
    @Synchronized
    fun cleanOldLogs(retentionDays: Int = 30) {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -retentionDays)
        val thresholdDate = calendar.time

        // 1. 清理日志文件 (格式为 log_yyyyMMdd.json)
        val logFiles = logsDir.listFiles { _, name -> name.startsWith("log_") && name.endsWith(".json") }
        val logDateFormatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        
        logFiles?.forEach { file ->
            try {
                val datePart = file.name.substringAfter("log_").substringBefore(".json")
                val fileDate = logDateFormatter.parse(datePart)
                if (fileDate != null && fileDate.before(thresholdDate)) {
                    if (file.delete()) {
                        Log.i(TAG, "已删除过期日志文件: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                // 如果文件名解析失败，基于最后修改时间兜底
                if (Date(file.lastModified()).before(thresholdDate)) {
                    file.delete()
                }
            }
        }

        // 2. 清理图片文件 (格式为 IMG_yyyyMMdd_HHmmss.jpg)
        val photoFiles = imagesDir.listFiles { _, name -> name.startsWith("IMG_") && name.endsWith(".jpg") }
        val photoDateFormatter = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        
        photoFiles?.forEach { file ->
            try {
                val datePart = file.name.substringAfter("IMG_").substringBefore("_")
                val fileDate = photoDateFormatter.parse(datePart)
                if (fileDate != null && fileDate.before(thresholdDate)) {
                    if (file.delete()) {
                        Log.i(TAG, "已删除过期照片文件: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                if (Date(file.lastModified()).before(thresholdDate)) {
                    file.delete()
                }
            }
        }
    }
}
