package com.water.von.ui.screens

import android.content.Context
import android.widget.Toast
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
 * 本地配置页面 StationSettingsScreen
 * 提供站点英文名（MQTT 设备标识）、站点中文名（界面全局标题）、通知开关与 GPS 开关的编辑与持久化保存
 * 保存时若传感器调试正在运行，自动停止调试后再保存生效
 */
@Composable
fun StationSettingsScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var englishName by remember { mutableStateOf("") }
    var chineseName by remember { mutableStateOf("") }
    var showNotificationPopup by remember { mutableStateOf(true) }
    var useNotificationSound by remember { mutableStateOf(true) }
    var useGpsPositioning by remember { mutableStateOf(true) }

    // 响应式订阅 MQTT 调试状态与统一控制响应
    val isMqttConnected by MqttService.isConnected.collectAsState()
    val isMqttDebuggingActive by MqttService.isMqttDebuggingActive.collectAsState()
    val latestControlResponse by MqttService.latestControlResponse.collectAsState()

    var rebootTarget by remember { mutableStateOf<String?>(null) } // "relay", "sensor", "camera", "all"
    var isRebooting by remember { mutableStateOf(false) }
    var rebootStatusText by remember { mutableStateOf<String?>(null) }

    // 监听全站控制平面响应
    LaunchedEffect(latestControlResponse) {
        latestControlResponse?.let { resp ->
            val fromName = when (resp.from) {
                "relay" -> "继电器网关"
                "sensor" -> "传感器节点"
                "camera" -> "摄像头"
                else -> resp.from
            }
            if (resp.status == "accept" && resp.event == "restarting") {
                rebootStatusText = "⏳ [$fromName] 已受理复位，正在重启（约 10 秒后就绪）..."
                Toast.makeText(context, "$fromName 已确认重启...", Toast.LENGTH_SHORT).show()
            } else if (resp.event == "online") {
                rebootStatusText = "✅ [$fromName] 重启成功，已重新上线并就绪！"
                isRebooting = false
                Toast.makeText(context, "🎉 $fromName 已恢复在线！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 首次进入加载已保存的配置
    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        englishName = sp.getString("prefix_name", "home") ?: "home"
        chineseName = sp.getString("station_chinese_name", "工厂之家") ?: "工厂之家"
        showNotificationPopup = sp.getBoolean("show_notification_popup", true)
        useNotificationSound = sp.getBoolean("use_notification_sound", true)
        useGpsPositioning = sp.getBoolean("use_gps_positioning", true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "本地配置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (isMqttDebuggingActive) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Text(
                    text = "ℹ️ 传感器调试正在运行。点击【停止调试并保存配置】将自动停止调试并立即生效。",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = "📝 配置编辑模式。站点英文名对应节点筛选标识，站点中文名对应界面顶部标题。",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        OutlinedTextField(
            value = englishName,
            onValueChange = { englishName = it },
            label = { Text("站点英文名 (设备标识)") },
            placeholder = { Text("例如: home") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = chineseName,
            onValueChange = { chineseName = it },
            label = { Text("站点中文名 (系统标题)") },
            placeholder = { Text("例如: 工厂之家") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Switch 1: show_notification_popup
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("弹出通知消息框", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("有异常报警时，系统是否通过通知气泡/消息框进行强提醒", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = showNotificationPopup,
                onCheckedChange = { showNotificationPopup = it }
            )
        }

        // Switch 2: use_notification_sound
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("使用声音通知", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("收到报警通知时，系统是否伴随警报铃声播放", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = useNotificationSound,
                onCheckedChange = { useNotificationSound = it }
            )
        }

        // Switch 3: use_gps_positioning
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("使用 GPS 定位", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("调试传感器或连接硬件时，是否启用 GPS 定位服务", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = useGpsPositioning,
                onCheckedChange = { useGpsPositioning = it }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                // 若传感器调试正在运行，自动停止后再保存配置
                if (isMqttDebuggingActive) {
                    MqttService.stopMqttDebugging(context)
                }

                val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                sp.edit().apply {
                    putString("prefix_name", englishName)
                    putString("station_chinese_name", chineseName)
                    putBoolean("show_notification_popup", showNotificationPopup)
                    putBoolean("use_notification_sound", useNotificationSound)
                    putBoolean("use_gps_positioning", useGpsPositioning)
                    // 明确写入 mqtt_debugging_active = false，确保状态同步
                    putBoolean("mqtt_debugging_active", false)
                    apply()
                }

                // 同步更新全局的中英文参数
                MqttService.updateStationChineseName(context, chineseName)
                MqttService.updateStationEnglishName(context, englishName)

                Toast.makeText(context, "本地配置已保存，立即生效", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isMqttDebuggingActive) "停止调试并保存配置" else "保存配置",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ── 全站硬件节点统一远程控制 ─────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("🛠️ 硬件节点维护与统一控制平面", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = "基于水文站统一控制平面 (water/{$englishName}/control)，支持指定硬件节点或全站节点进行安全远程复位。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 节点单控网格
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { rebootTarget = "relay" },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = !isRebooting
                    ) {
                        Text("🔄 继电器网关", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { rebootTarget = "sensor" },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = !isRebooting
                    ) {
                        Text("🔄 传感器节点", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { rebootTarget = "camera" },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = !isRebooting
                    ) {
                        Text("🔄 摄像头节点", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 全站一键复位
                Button(
                    onClick = { rebootTarget = "all" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    enabled = !isRebooting
                ) {
                    Text(
                        text = if (isRebooting) "⏳ 执行中..." else "⚡ 一键复位全站所有设备 (All)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                rebootStatusText?.let { txt ->
                    Text(
                        text = txt,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 统一控制确认对话框
        rebootTarget?.let { target ->
            val targetLabel = when (target) {
                "relay" -> "继电器智能网关"
                "sensor" -> "水位传感器节点"
                "camera" -> "ESP32-CAM 摄像头"
                "all" -> "全站所有硬件设备 (Relay + Sensor + Camera)"
                else -> target
            }
            AlertDialog(
                onDismissRequest = { rebootTarget = null },
                title = { Text("确认远程重启？", fontWeight = FontWeight.Bold) },
                text = {
                    Text("即将向站点 [$englishName] 下发统一控制指令复位【$targetLabel】。\n\n目标节点将短暂断开网络并在约 10 秒后自动重新上线。")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val selectedTarget = target
                            rebootTarget = null
                            if (!isMqttConnected) {
                                Toast.makeText(context, "MQTT 未连接", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isRebooting = true
                            rebootStatusText = "⏳ 正在向 [$englishName] 发送 [$targetLabel] 复位指令..."
                            val cmdId = System.currentTimeMillis().toString()
                            val json = JSONObject().apply {
                                put("cmd_id", cmdId)
                                put("target", selectedTarget)
                                put("action", "restart")
                            }
                            val topic = MqttTopics.getControlTopic(englishName)
                            MqttService.publish(context, topic, json.toString())
                            Toast.makeText(context, "已发送复位指令: $targetLabel", Toast.LENGTH_SHORT).show()

                            coroutineScope.launch {
                                delay(15000)
                                if (isRebooting) {
                                    isRebooting = false
                                    rebootStatusText = "⚠️ 重启响应超时 (15s 未收到上线通知)"
                                    Toast.makeText(context, "重启超时", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("立即重启")
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
}
