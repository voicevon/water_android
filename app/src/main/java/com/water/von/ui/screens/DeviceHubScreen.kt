package com.water.von.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.von.service.MqttService
import com.water.von.utils.MqttTopics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 硬件设备工作台 (DeviceHubScreen)
 *
 * 方案 B 核心中枢：物理三节点（Relay / Sensor / Camera）健康状况与统一运维大屏
 * 1. 集中展示继电器网关、24位 AD 传感器、巡视相机的在线状态与核心工作参数；
 * 2. 彻底移除废弃的重量/克数标定，传感器聚焦纯正的 24 位 AD 码值与波形偏置调试；
 * 3. 支持针对各硬件节点独立下发安全重启，或全站一键复位 (All)；
 * 4. 提供直达【传感器 AD 波形调试】与【摄像头突变配置】的无缝下钻入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHubScreen(
    onOpenSensorDebug: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenStationSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 全局状态与服务连接
    val isConnected by MqttService.isConnected.collectAsState()
    val stationChineseName by MqttService.stationChineseName.collectAsState()
    val latestSensorAd by MqttService.latestSensorAdData.collectAsState()
    val latestControlResponse by MqttService.latestControlResponse.collectAsState()

    // 本地持久化配置读取（统一对齐至 mqtt_debug_config）
    val sp = remember { context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE) }
    val camSp = remember { context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE) }
    val stationSp = remember { context.getSharedPreferences("station_settings", Context.MODE_PRIVATE) }

    var englishName by remember { mutableStateOf(sp.getString("prefix_name", "home") ?: "home") }
    var pumpCh1Time by remember { mutableStateOf(stationSp.getInt("channel1_time", 45)) }
    var pumpCh2Time by remember { mutableStateOf(stationSp.getInt("channel2_time", 30)) }
    var pumpCh3Time by remember { mutableStateOf(stationSp.getInt("channel3_time", 30)) }

    var mutEnable by remember { mutableStateOf(camSp.getBoolean("mut_enable", true)) }
    var mutInterval by remember { mutableStateOf(camSp.getInt("mut_interval_sec", 10)) }

    // 进入页面时动态刷新本地配置参数
    LaunchedEffect(Unit) {
        englishName = sp.getString("prefix_name", "home") ?: "home"
        pumpCh1Time = stationSp.getInt("channel1_time", 45)
        pumpCh2Time = stationSp.getInt("channel2_time", 30)
        pumpCh3Time = stationSp.getInt("channel3_time", 30)
        mutEnable = camSp.getBoolean("mut_enable", true)
        mutInterval = camSp.getInt("mut_interval_sec", 10)
    }

    // 复位弹窗与状态机
    var rebootTarget by remember { mutableStateOf<String?>(null) } // "relay", "sensor", "camera", "all"
    var isRebooting by remember { mutableStateOf(false) }
    var rebootStatusText by remember { mutableStateOf<String?>(null) }

    // 监听全站控制平面响应
    LaunchedEffect(latestControlResponse) {
        latestControlResponse?.let { resp ->
            val fromName = when (resp.from) {
                "relay" -> "继电器网关"
                "sensor" -> "水位传感器"
                "camera" -> "抓拍摄像头"
                else -> resp.from
            }
            if (resp.status == "accept" && resp.event == "restarting") {
                rebootStatusText = "⏳ [$fromName] 已受理复位指令，正在重启（约 10 秒后就绪）..."
                Toast.makeText(context, "$fromName 已确认重启...", Toast.LENGTH_SHORT).show()
            } else if (resp.event == "online") {
                rebootStatusText = "✅ [$fromName] 重启成功，已重新上线并就绪！"
                isRebooting = false
                Toast.makeText(context, "🎉 $fromName 已恢复在线！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 1. 全局控制与站点健康卡片 ─────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "目标站点: $stationChineseName ($englishName)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isConnected) "● MQTT 通信就绪" else "○ Broker 未连接",
                            fontSize = 12.sp,
                            color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = { rebootTarget = "all" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        enabled = !isRebooting
                    ) {
                        Text("⚡ 全站节点复位", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                rebootStatusText?.let { status ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = status,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ── 2. 节点 1：继电器智能网关 (Relay) ────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎛️ 继电器采样网关 (Relay)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = if (isConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (isConnected) "在线运行" else "离线",
                            fontSize = 11.sp,
                            color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "抽水参数：Ch1: ${pumpCh1Time}s | Ch2: ${pumpCh2Time}s | Ch3: ${pumpCh3Time}s\n状态上报主题：water/$englishName/state",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenStationSettings,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("⏱️ 调整抽水参数", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { rebootTarget = "relay" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED6C02)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(36.dp),
                        enabled = !isRebooting,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("🔄 重启网关", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        // ── 3. 节点 2：水位力敏传感器 (HX711 24-bit AD) ────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚖️ 水位力敏传感器 (24位 AD 计数)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = Color(0xFFE0F2FE),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "AD 架构 (无克数换算)",
                            fontSize = 11.sp,
                            color = Color(0xFF0284C7),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                val adText = if (latestSensorAd != null && latestSensorAd!!.size >= 3) {
                    "Ch1: ${latestSensorAd!![0]}  |  Ch2: ${latestSensorAd!![1]}  |  Ch3: ${latestSensorAd!![2]}"
                } else {
                    "Ch1: --  |  Ch2: --  |  Ch3: --"
                }

                Text(
                    text = "三路实时 AD 读数：$adText\n工作机制：全链路 24 位直接 AD 转码操作，移位 N=6，无物理皮重换算",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenSensorDebug,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.2f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("📈 AD 波形与偏置调试", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { rebootTarget = "sensor" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED6C02)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.8f).height(36.dp),
                        enabled = !isRebooting,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("🔄 重启传感器", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        // ── 4. 节点 3：抓拍摄像头 (Camera) ──────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📷 巡视抓拍摄像头 (Camera)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        color = if (mutEnable) Color(0xFFE8F5E9) else Color(0xFFF1F5F9),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (mutEnable) "8x8突变已启用" else "突变已停用",
                            fontSize = 11.sp,
                            color = if (mutEnable) Color(0xFF2E7D32) else Color(0xFF64748B),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "巡视周期：每 $mutInterval 秒 / 帧\n算法规格：8x8 网格方差比对，硬件 NVS 参数自动同步",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenCameraSettings,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.2f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("⚙️ 突变检测配置", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { rebootTarget = "camera" },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFED6C02)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(0.8f).height(36.dp),
                        enabled = !isRebooting,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("🔄 重启摄像头", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }

    // ── 确认复位模态对话框 ──────────────────────────────────
    if (rebootTarget != null) {
        val targetName = when (rebootTarget) {
            "relay" -> "继电器网关"
            "sensor" -> "水位传感器"
            "camera" -> "抓拍摄像头"
            "all" -> "全站所有节点 (Relay + Sensor + Camera)"
            else -> rebootTarget ?: ""
        }

        AlertDialog(
            onDismissRequest = { rebootTarget = null },
            title = {
                Text(
                    text = "⚠️ 确认远程重启设备？",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "即将向【$targetName】下发远程复位控制指令：",
                        fontSize = 14.sp
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Topic: ${MqttTopics.getControlTopic(englishName)}\nPayload: {\"target\":\"$rebootTarget\",\"action\":\"restart\"}",
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Text(
                        text = "⚠️ 设备将短暂离线并在约 10 秒后自动重新上线。期间相关传感器或通道将暂时无法采集。",
                        fontSize = 12.sp,
                        color = Color(0xFFD32F2F)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val currentTarget = rebootTarget ?: return@Button
                        rebootTarget = null
                        isRebooting = true
                        rebootStatusText = "📡 正在向下发复位指令..."

                        coroutineScope.launch {
                            val cmdId = System.currentTimeMillis().toString()
                            val payload = JSONObject().apply {
                                put("cmd_id", cmdId)
                                put("target", currentTarget)
                                put("action", "restart")
                            }.toString()

                            val topic = MqttTopics.getControlTopic(englishName)
                            MqttService.publish(context, topic, payload)

                            delay(3000)
                            if (isRebooting && latestControlResponse?.status != "accept") {
                                rebootStatusText = "⚠️ 指令已发布，正在等待节点 pre-ACK 回执..."
                            }

                            // 15 秒超时兜底机制：防止目标设备断电或离线导致重启状态永久卡死
                            delay(12000)
                            if (isRebooting) {
                                isRebooting = false
                                rebootStatusText = "⚠️ 节点响应超时（15 秒未收到上线通知，请检查设备供电或网络）"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("确认立即重启", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { rebootTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}
