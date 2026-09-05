package com.water.von.detection

import android.graphics.BitmapFactory
import android.util.Log

/**
 * MutationDetector - Android 端图像突变检测器
 *
 * 算法与 ESP32 端 [water_camera/src/mutation_detector.cpp] 完全等价，
 * 支持参数相互移植：
 *   - MD_GRID_W / MD_GRID_H : 8×8 网格
 *   - MD_WINDOW_SIZE         : 20 帧滑动窗口
 *   - blockThresh            : 对应 get_mutation_block_thresh()，默认 0.15f
 *   - minBlocks              : 对应 get_mutation_min_blocks()，默认 3
 *
 * 算法流程（与 ESP32 一致）：
 *  1. JPEG 解码 → 8×8 网格 RGB888 均值（Android 端用 BitmapFactory 代替 esp_jpg_decode）
 *  2. 计算全图亮度 Y_global → 全局光照归一化（自适应光线变化）
 *  3. Y_global < 1.0 → 极暗保护，跳过报警，仅更新基线
 *  4. 前 MD_WINDOW_SIZE 帧预热期，仅积累基线不报警
 *  5. 逐网格计算三通道相对偏差率 Δ_i，统计超阈值网格数 c_changed
 *  6. c_changed >= minBlocks → 触发报警
 *  7. 每帧均更新滑动窗口均值 _mu（持续自适应环境漂移）
 */
class MutationDetector {

    companion object {
        private const val TAG = "MutationDetector"

        const val MD_GRID_W     = 8    // 水平方向网格数（与 ESP32 端相同）
        const val MD_GRID_H     = 8    // 垂直方向网格数（与 ESP32 端相同）
        const val MD_GRID_COUNT = 64   // 总网格数 (8×8)（与 ESP32 端相同）
        const val MD_WINDOW_SIZE = 20  // 滑动窗口容量（帧数）（与 ESP32 端相同）

        /** 参数默认值（与 ESP32 nvs_config.cpp 默认值保持一致） */
        const val DEFAULT_BLOCK_THRESH = 0.15f  // mut_block_thresh 默认 0.15
        const val DEFAULT_MIN_BLOCKS   = 3      // mut_min_blocks 默认 3
    }

    // ============================================================
    //  滑动窗口与移动均值（对应 ESP32 _window / _mu）
    // ============================================================
    private val window = Array(MD_WINDOW_SIZE) { Array(MD_GRID_COUNT) { FloatArray(3) } }
    private var writeIdx   = 0
    private var frameCount = 0

    /** 各网格移动均值（预计算，随窗口更新同步刷新）*/
    private val mu = Array(MD_GRID_COUNT) { FloatArray(3) }

    // ============================================================
    //  调试监控数据
    // ============================================================
    var lastYGlobal:   Float   = 0f; private set
    var lastCChanged:  Int     = 0;  private set
    var lastAlarm:     Boolean = false; private set

    // ============================================================
    //  私有：JPEG 解码并降采样至 8×8 网格 RGB 均值
    //  对应 ESP32 _decodeGrid()：使用 JPG_SCALE_8X + 网格累加
    // ============================================================
    private fun decodeGrid(jpegBytes: ByteArray, gridRgb: Array<FloatArray>): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                // inSampleSize=8 近似等价于 ESP32 JPG_SCALE_8X，VGA(640×480) → ~80×60
                inSampleSize = 8
            }
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
                ?: return false

            val imgW = bitmap.width
            val imgH = bitmap.height

            // 网格累加缓冲（对应 DecodeArg.grid_r/g/b / pix_cnt）
            val gridR   = FloatArray(MD_GRID_COUNT)
            val gridG   = FloatArray(MD_GRID_COUNT)
            val gridB   = FloatArray(MD_GRID_COUNT)
            val pixCnt  = IntArray(MD_GRID_COUNT)

            // 逐像素映射到对应网格并累加 RGB
            for (py in 0 until imgH) {
                for (px in 0 until imgW) {
                    val gx = if (imgW > 0) (px.toLong() * MD_GRID_W / imgW).toInt().coerceIn(0, MD_GRID_W - 1) else 0
                    val gy = if (imgH > 0) (py.toLong() * MD_GRID_H / imgH).toInt().coerceIn(0, MD_GRID_H - 1) else 0
                    val idx = gy * MD_GRID_W + gx
                    val pixel = bitmap.getPixel(px, py)
                    gridR[idx] += ((pixel shr 16) and 0xFF).toFloat()
                    gridG[idx] += ((pixel shr 8)  and 0xFF).toFloat()
                    gridB[idx] += (pixel           and 0xFF).toFloat()
                    pixCnt[idx]++
                }
            }
            bitmap.recycle()

            // 各网格除以像素计数，得到 RGB 均值（对应 ESP32 _decodeGrid 最后循环）
            for (i in 0 until MD_GRID_COUNT) {
                if (pixCnt[i] > 0) {
                    gridRgb[i][0] = gridR[i] / pixCnt[i]
                    gridRgb[i][1] = gridG[i] / pixCnt[i]
                    gridRgb[i][2] = gridB[i] / pixCnt[i]
                } else {
                    gridRgb[i][0] = 0f; gridRgb[i][1] = 0f; gridRgb[i][2] = 0f
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "decodeGrid failed: ${e.message}")
            false
        }
    }

    // ============================================================
    //  私有：将新帧归一化特征存入滑动窗口，重新计算 _mu
    //  对应 ESP32 _updateWindow()
    // ============================================================
    private fun updateWindow(normRgb: Array<FloatArray>) {
        // 写入环形缓冲
        for (i in 0 until MD_GRID_COUNT) {
            window[writeIdx][i][0] = normRgb[i][0]
            window[writeIdx][i][1] = normRgb[i][1]
            window[writeIdx][i][2] = normRgb[i][2]
        }
        writeIdx = (writeIdx + 1) % MD_WINDOW_SIZE
        if (frameCount < MD_WINDOW_SIZE) frameCount++

        // 重新计算各网格移动均值
        for (i in 0 until MD_GRID_COUNT) { mu[i][0] = 0f; mu[i][1] = 0f; mu[i][2] = 0f }
        for (f in 0 until frameCount) {
            for (i in 0 until MD_GRID_COUNT) {
                mu[i][0] += window[f][i][0]
                mu[i][1] += window[f][i][1]
                mu[i][2] += window[f][i][2]
            }
        }
        val n = frameCount.toFloat()
        for (i in 0 until MD_GRID_COUNT) {
            mu[i][0] /= n; mu[i][1] /= n; mu[i][2] /= n
        }
    }

    // ============================================================
    //  主接口：处理一帧 JPEG 字节，返回是否触发突变报警
    //  对应 ESP32 MutationDetector::processFrame()
    //
    //  @param jpegBytes JPEG 原始字节（来自 MQTT water/photo/status/+）
    //  @param blockThresh 网格偏差率阈值（对应 get_mutation_block_thresh()）
    //  @param minBlocks   最少触发网格数（对应 get_mutation_min_blocks()）
    //  @return true = 检测到突变，false = 无突变/预热期/极暗
    // ============================================================
    fun processFrame(
        jpegBytes:   ByteArray,
        blockThresh: Float = DEFAULT_BLOCK_THRESH,
        minBlocks:   Int   = DEFAULT_MIN_BLOCKS
    ): Boolean {
        // Step 1：JPEG 解码 → 8×8 网格 RGB 均值
        val gridRgb = Array(MD_GRID_COUNT) { FloatArray(3) }
        if (!decodeGrid(jpegBytes, gridRgb)) return false

        // Step 2：计算全图平均总亮度 Y_global（三通道均值的全局均值）
        var yGlobal = 0f
        for (i in 0 until MD_GRID_COUNT) {
            yGlobal += gridRgb[i][0] + gridRgb[i][1] + gridRgb[i][2]
        }
        yGlobal /= (MD_GRID_COUNT * 3).toFloat()

        // Step 3：全局光照归一化，Y_global < 1.0 为极暗场景（对应 ESP32 Step 3）
        val normRgb = Array(MD_GRID_COUNT) { FloatArray(3) }
        if (yGlobal < 1.0f) {
            Log.d(TAG, "Y_global=%.2f < 1.0, too dark. Updating baseline only.".format(yGlobal))
            val zeroed = Array(MD_GRID_COUNT) { FloatArray(3) }
            updateWindow(zeroed)
            lastYGlobal = yGlobal; lastCChanged = 0; lastAlarm = false
            return false
        }

        for (i in 0 until MD_GRID_COUNT) {
            normRgb[i][0] = gridRgb[i][0] / yGlobal
            normRgb[i][1] = gridRgb[i][1] / yGlobal
            normRgb[i][2] = gridRgb[i][2] / yGlobal
        }

        // Step 4：预热期检查（前 MD_WINDOW_SIZE 帧仅积累基线，不执行报警）
        if (frameCount < MD_WINDOW_SIZE) {
            Log.d(TAG, "Warming up (${frameCount + 1}/$MD_WINDOW_SIZE). Y_global=%.1f".format(yGlobal))
            updateWindow(normRgb)
            lastYGlobal = yGlobal; lastCChanged = 0; lastAlarm = false
            return false
        }

        // Step 5：逐网格计算三通道偏差率均值 Δ_i，统计超阈值网格数（对应 ESP32 Step 5）
        var cChanged = 0
        for (i in 0 until MD_GRID_COUNT) {
            var delta = 0f
            for (ch in 0..2) {
                if (mu[i][ch] > 1e-6f) {
                    delta += Math.abs(normRgb[i][ch] - mu[i][ch]) / mu[i][ch]
                }
            }
            delta /= 3f  // 三通道偏差率取均值
            if (delta > blockThresh) cChanged++
        }

        // Step 6：先更新滑动窗口（不论判定结果如何，实现持续自适应）
        updateWindow(normRgb)

        // Step 7：突变判定
        val alarm = cChanged >= minBlocks

        Log.d(TAG, "Y_global=%.1f, C_changed=%d/%d, thresh=%.2f, alarm=%s"
            .format(yGlobal, cChanged, MD_GRID_COUNT, blockThresh, if (alarm) "YES" else "NO"))

        lastYGlobal = yGlobal
        lastCChanged = cChanged
        lastAlarm = alarm
        return alarm
    }

    /**
     * 重置检测器状态（清空滑动窗口和均值，重新进入预热期）
     * 用于手动重置基线或站点切换后重新校准
     */
    fun reset() {
        for (f in window) for (g in f) { g[0] = 0f; g[1] = 0f; g[2] = 0f }
        for (m in mu) { m[0] = 0f; m[1] = 0f; m[2] = 0f }
        writeIdx = 0; frameCount = 0
        lastYGlobal = 0f; lastCChanged = 0; lastAlarm = false
        Log.d(TAG, "MutationDetector reset. Re-entering warm-up period.")
    }
}
