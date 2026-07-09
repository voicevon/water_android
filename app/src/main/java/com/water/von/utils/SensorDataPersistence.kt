package com.water.von.utils

import android.content.Context
import android.util.Log
import com.water.von.ui.components.SensorDataPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object SensorDataPersistence {
    private const val TAG = "SensorDataPersistence"

    /**
     * 从本地沙盒中加载指定文件的历史数据点
     */
    fun loadDataPoints(context: Context, fileName: String): List<List<SensorDataPoint>> {
        val result = List(3) { mutableListOf<SensorDataPoint>() }
        try {
            val file = File(context.filesDir, fileName)
            if (!file.exists()) {
                return result
            }
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            for (i in 0 until jsonArray.length().coerceAtMost(3)) {
                val channelArray = jsonArray.getJSONArray(i)
                val channelList = result[i]
                for (j in 0 until channelArray.length()) {
                    val obj = channelArray.getJSONObject(j)
                    val point = SensorDataPoint(
                        ch0 = obj.optInt("ch0", 0),
                        ch1 = obj.optInt("ch1", 0),
                        ch2 = obj.optInt("ch2", 0),
                        ch3 = obj.optInt("ch3", 0),
                        hasWater = obj.optBoolean("hasWater", false),
                        hasWaterRemote = obj.optBoolean("hasWaterRemote", false)
                    )
                    channelList.add(point)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载传感器历史调试数据失败: $fileName", e)
        }
        return result
    }

    /**
     * 将 4 个通道的数据点列表保存至本地沙盒中
     */
    fun saveDataPoints(context: Context, fileName: String, dataPoints: Array<List<SensorDataPoint>>) {
        try {
            val jsonArray = JSONArray()
            for (i in 0 until 3) {
                val channelArray = JSONArray()
                val list = dataPoints[i]
                for (point in list) {
                    val obj = JSONObject().apply {
                        put("ch0", point.ch0)
                        put("ch1", point.ch1)
                        put("ch2", point.ch2)
                        put("ch3", point.ch3)
                        put("hasWater", point.hasWater)
                        put("hasWaterRemote", point.hasWaterRemote)
                    }
                    channelArray.put(obj)
                }
                jsonArray.put(channelArray)
            }
            val file = File(context.filesDir, fileName)
            file.writeText(jsonArray.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "保存传感器调试数据失败: $fileName", e)
        }
    }
}
