package com.water.von.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.von.detection.MutationDetector
import com.water.von.service.MqttService
import com.water.von.utils.MqttTopics

/**
 * 摄像头巡视配置页面 CameraSettingsScreen
 *
 * 配置参数与 ESP32 端 nvs_config（camera_cfg 命名空间）完全对应，
 * 支持在 Android 端实时配置并组装 JSON 字符串下发至 ESP32 相机节点：
 *
 * | Android 键名（mqtt_debug_config）  | ESP32 NVS 键名    | 含义         |
 * |-------------------------------------|-------------------|--------------|
 * | photo_mode                          | photo_mode        | 运行模式(once/interval/motion) |
 * | mut_enable                          | mut_enable        | 总开关       |
 * | mut_interval_sec                    | mut_interval      | 检测间隔(秒) |
 * | mut_block_thresh (×100 存为 Int)   | mut_thresh        | 网格偏差阈值 |
 * | mut_min_blocks                      | mut_min_blk       | 最少变化网格 |
 */
@Composable
fun CameraSettingsScreen() {
    val context = LocalContext.current

    // ── 配置状态（与 ESP32 默认值一致）─────────────────────────────
    var mutEnable       by remember { mutableStateOf(true) }
    var intervalSec     by remember { mutableStateOf(10)   }    // 5 ~ 300 秒
    var blockThreshPct  by remember { mutableStateOf(15)   }    // 5 ~ 50（对应 0.05~0.50）
    var minBlocks       by remember { mutableStateOf(3)    }    // 1 ~ 32

    // ── 首次进入加载已保存配置 ────────────────────────────────────
    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        mutEnable      = sp.getBoolean("mut_enable",       true)
        intervalSec    = sp.getInt("mut_interval_sec",     10)
        blockThreshPct = sp.getInt("mut_block_thresh_pct", 15)
        minBlocks      = sp.getInt("mut_min_blocks",       3)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── 标题 ─────────────────────────────────────────────────
        Text(
            text = "摄像头巡视与模式配置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Text(
                text = "📷 相机平时处于待命状态，收到拍照指令即拍摄上传。开启异物突变检测后，检测到入侵物体将主动报警拍照。手机端定时巡视任务将按设定周期向相机发送拍照指令。",
                modifier = Modifier.padding(12.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        HorizontalDivider()

        // ── Section 1：总开关 ────────────────────────────────────
        Text(
            text = "突变检测总开关",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("启用摄像头入侵检测", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "开启后定时拍照并分析图像变化，检测到物体进入视野时报警。" +
                    "\n对应 ESP32 参数：mut_enable",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = mutEnable,
                onCheckedChange = { mutEnable = it }
            )
        }

        HorizontalDivider()

        // ── Section 2：检测间隔 ──────────────────────────────────
        Text(
            text = "检测/定时拍照间隔",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("拍照间隔 (Interval)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "每隔多少秒下发/触发一次拍照。" +
                        "\n对应 ESP32 参数：interval（范围 5~300 秒）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 显示当前值
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = formatInterval(intervalSec),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Slider(
                value = intervalSec.toFloat(),
                onValueChange = { intervalSec = it.toInt() },
                valueRange = 5f..300f,
                steps = 58, // (300-5)/5 - 1
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("5 秒", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("5 分钟", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        // ── Section 3：网格偏差阈值 ──────────────────────────────
        Text(
            text = "检测灵敏度参数",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("网格偏差阈值", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "单个 8×8 网格的三通道归一化偏差率超过此值时计入变化网格。" +
                        "值越小越灵敏。" +
                        "\n对应 ESP32 参数：mut_thresh（范围 0.05~0.50）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%d%%  (%.2f)", blockThreshPct, blockThreshPct / 100f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Slider(
                value = blockThreshPct.toFloat(),
                onValueChange = { blockThreshPct = it.toInt() },
                valueRange = 5f..50f,
                steps = 44, // 45 个整数步
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("5%  极灵敏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("50%  低灵敏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("最少变化网格数", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "超过偏差阈值的网格数 ≥ 此值时触发报警。" +
                        "总共 64 个网格（8×8），值越小越灵敏。" +
                        "\n对应 ESP32 参数：mut_min_blk（范围 1~32）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "$minBlocks / ${MutationDetector.MD_GRID_COUNT} 格",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Slider(
                value = minBlocks.toFloat(),
                onValueChange = { minBlocks = it.toInt() },
                valueRange = 1f..32f,
                steps = 30,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1 格  极灵敏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("32 格  低灵敏", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        // ── 算法说明卡片 ─────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("🧠 算法说明", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "• 8×8 网格：图像被均分为 64 个区域，逐区计算颜色变化\n" +
                    "• 全局光照归一化：自动适应白天/黑夜光线变化，消除误报\n" +
                    "• 20 帧滑动窗口：前 20 次拍照为预热期，仅积累基线不报警\n" +
                    "• 持续自适应：每帧都更新均值基线，适应缓慢环境变化",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 保存按钮 ─────────────────────────────────────────────
        Button(
            onClick = {
                val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                sp.edit().apply {
                    putBoolean("mut_enable",           mutEnable)
                    putInt("mut_interval_sec",         intervalSec)
                    putInt("mut_block_thresh_pct",     blockThreshPct)
                    putInt("mut_min_blocks",           minBlocks)
                    apply()
                }

                // 组装符合规范的异物检测开关指令下发给 ESP32 CAM 节点
                val jsonPayload = org.json.JSONObject().apply {
                    put("site_name", MqttService.stationEnglishName.value)
                    put("motion", if (mutEnable) "on" else "off")
                }.toString()

                MqttService.publish(context, MqttTopics.CONTROL_TAKE_PHOTO, jsonPayload)

                // 通知 MqttService 更新定时任务
                MqttService.applyPeriodicPhotoSettings(context)
                Toast.makeText(context, "相机配置已保存并同步下发至 ESP32", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("保存配置并同步下发至相机", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/** 将秒数格式化为"X 秒"或"X 分 Y 秒" */
private fun formatInterval(sec: Int): String {
    return if (sec < 60) "${sec} 秒"
    else if (sec % 60 == 0) "${sec / 60} 分钟"
    else "${sec / 60} 分 ${sec % 60} 秒"
}
