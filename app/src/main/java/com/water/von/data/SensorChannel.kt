package com.water.von.data

import com.water.von.utils.DataProcessor

/**
 * 传感器有水/无水状态枚举
 */
enum class SensorState(val chineseName: String) {
    NO_WATER("无水"),   // 干燥正常状态
    HAS_WATER("有水")   // 触水触发状态
}

/**
 * 传感器物理通道控制类
 * 持有独立的滤波计算器，并实现状态锁存及施密特触发器逻辑
 */
class SensorChannel(
    val id: Int,
    var thresholdOffset: Int = 50
) {
    private val dataProcessor = DataProcessor()

    var rawValue: Int = 0
        private set
    var filteredValue: Int = 0
        private set
    var baseline: Int = 0
        private set
    var lastState: SensorState = SensorState.NO_WATER
        private set

    /**
     * 动态计算当前的触发阈值：
     * - 干燥无水状态：阈值为 baseline + thresholdOffset (高阈值，需要上穿触发)
     * - 触水有水状态：阈值为 baseline - thresholdOffset (低阈值，需要下穿恢复)
     */
    val threshold: Int
        get() = if (lastState == SensorState.NO_WATER) {
            baseline + thresholdOffset
        } else {
            baseline - thresholdOffset
        }

    /**
     * 喂入最新的传感器原始值，返回更新后的通道状态
     */
    fun pushRaw(value: Int): SensorState {
        rawValue = value
        filteredValue = dataProcessor.pushRaw(value)
        baseline = dataProcessor.pushBaseline(filteredValue)

        // 施密特双向触发器状态机
        val currentState = lastState
        lastState = when (currentState) {
            SensorState.NO_WATER -> {
                if (filteredValue > threshold) {
                    SensorState.HAS_WATER
                } else {
                    SensorState.NO_WATER
                }
            }
            SensorState.HAS_WATER -> {
                if (filteredValue < threshold) {
                    SensorState.NO_WATER
                } else {
                    SensorState.HAS_WATER
                }
            }
        }
        return lastState
    }

    /**
     * 重置通道状态及滤波数据
     */
    fun reset() {
        dataProcessor.reset()
        rawValue = 0
        filteredValue = 0
        baseline = 0
        lastState = SensorState.NO_WATER
    }
}
