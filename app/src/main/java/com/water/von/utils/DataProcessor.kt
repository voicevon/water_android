package com.water.von.utils

class DataProcessor {
    companion object {
        const val MA_WINDOW = 50
        const val BASELINE_WINDOW = 200
        const val THRESHOLD_OFFSET = 5000
    }

    private val maBuf = IntArray(MA_WINDOW)
    private var maHead = 0
    private var maCount = 0
    private var maSum = 0L

    private val baseBuf = IntArray(BASELINE_WINDOW)
    private var baseHead = 0
    private var baseCount = 0
    private var baseSum = 0L

    fun reset() {
        maHead = 0
        maCount = 0
        maSum = 0L
        baseHead = 0
        baseCount = 0
        baseSum = 0L
        maBuf.fill(0)
        baseBuf.fill(0)
    }

    fun pushRaw(value: Int): Int {
        if (maCount < MA_WINDOW) {
            maBuf[maHead] = value
            maSum += value
            maCount++
        } else {
            maSum -= maBuf[maHead]
            maBuf[maHead] = value
            maSum += value
        }
        maHead = (maHead + 1) % MA_WINDOW
        return (maSum / maCount).toInt()
    }

    fun pushBaseline(value: Int): Int {
        if (baseCount < BASELINE_WINDOW) {
            baseBuf[baseHead] = value
            baseSum += value
            baseCount++
        } else {
            baseSum -= baseBuf[baseHead]
            baseBuf[baseHead] = value
            baseSum += value
        }
        baseHead = (baseHead + 1) % BASELINE_WINDOW
        return (baseSum / baseCount).toInt()
    }
}
