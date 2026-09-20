package com.water.von.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.water.von.service.MqttService
import com.water.von.utils.MqttTopics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * HX711 传感器标定与配置闭环弹窗页面
 *
 * 优化点：
 * 1. 顶部标题栏高度严格锁定为固定高度（48dp/横屏40dp），绝不膨胀占用屏幕；
 * 2. 控制操作（读取参数、实时流监听）置于内容顶部控制栏，横向紧凑美观；
 * 3. 5秒读取超时检测与明确状态提示；
 * 4. 单通道下发带“等待设备确认”与“确认成功”闭环反馈；
 * 5. 实时展示力值与 16-bit 原始 AD 码值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Hx711ConfigScreen(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val sp = remember { context.getSharedPreferences("hx711_calibration_prefs", Context.MODE_PRIVATE) }

    // 独立获取真实传感器设备名（来自固件回显，默认为 home），彻底解耦水文站名（如 dongzhan）
    val detectedSensorDeviceName by MqttService.detectedSensorDeviceName.collectAsState()

    // MQTT 连接与传感器最新数据订阅
    val isMqttConnected by MqttService.isConnected.collectAsState()
    val rawSensorData by MqttService.latestSensorRawData.collectAsState()
    val rawSensorAdData by MqttService.latestSensorAdData.collectAsState()
    val latestCalStatus by MqttService.latestCalibrationStatus.collectAsState()

    // 实时高频数据流激活状态（water/sensor/start）
    var isDataStreamActive by remember { mutableStateOf(false) }

    // 读取状态机
    var isReading by remember { mutableStateOf(false) }
    var readStatusText by remember { mutableStateOf<String?>(null) }
    var readStatusColor by remember { mutableStateOf(Color.Gray) }

    // 通道下发确认状态（-1 表示无等待，0~2 表示等待通道 1~3 的确认）
    var pendingConfirmChannel by remember { mutableStateOf(-1) }
    var targetConfirmK by remember { mutableStateOf(0f) }

    // 3 个通道独立的标定参数状态
    var ch1Enabled by remember { mutableStateOf(sp.getBoolean("ch1_enabled", true)) }
    var ch1K by remember { mutableStateOf(sp.getString("ch1_k", "1.0") ?: "1.0") }
    var ch1C by remember { mutableStateOf(sp.getString("ch1_c", "32768") ?: "32768") }
    var ch1Shift by remember { mutableStateOf(sp.getString("ch1_shift", "6") ?: "6") }

    var ch2Enabled by remember { mutableStateOf(sp.getBoolean("ch2_enabled", true)) }
    var ch2K by remember { mutableStateOf(sp.getString("ch2_k", "1.0") ?: "1.0") }
    var ch2C by remember { mutableStateOf(sp.getString("ch2_c", "32768") ?: "32768") }
    var ch2Shift by remember { mutableStateOf(sp.getString("ch2_shift", "6") ?: "6") }

    var ch3Enabled by remember { mutableStateOf(sp.getBoolean("ch3_enabled", true)) }
    var ch3K by remember { mutableStateOf(sp.getString("ch3_k", "1.0") ?: "1.0") }
    var ch3C by remember { mutableStateOf(sp.getString("ch3_c", "32768") ?: "32768") }
    var ch3Shift by remember { mutableStateOf(sp.getString("ch3_shift", "6") ?: "6") }

    // 保存配置至本地缓存
    fun saveToPrefs() {
        sp.edit().apply {
            putBoolean("ch1_enabled", ch1Enabled)
            putString("ch1_k", ch1K)
            putString("ch1_c", ch1C)
            putString("ch1_shift", ch1Shift)

            putBoolean("ch2_enabled", ch2Enabled)
            putString("ch2_k", ch2K)
            putString("ch2_c", ch2C)
            putString("ch2_shift", ch2Shift)

            putBoolean("ch3_enabled", ch3Enabled)
            putString("ch3_k", ch3K)
            putString("ch3_c", ch3C)
            putString("ch3_shift", ch3Shift)
            apply()
        }
    }

    val themePrimary = MaterialTheme.colorScheme.primary

    // 主动向设备发送查询请求并启动 5 秒超时计时
    fun queryCurrentCalibration() {
        if (!isMqttConnected) {
            Toast.makeText(context, "MQTT 未连接", Toast.LENGTH_SHORT).show()
            readStatusText = "⚠️ MQTT 未连接"
            readStatusColor = Color.Red
            return
        }

        isReading = true
        readStatusText = "⏳ 正在请求设备参数 (5s)..."
        readStatusColor = themePrimary

        // 发送查询 JSON：使用 name: "all" 与定向 "home" 双保险查询，确保各种固件版本皆能响应
        val queryJsonAll = JSONObject().apply {
            put("action", "query")
            put("query", true)
            put("name", "all")
        }
        MqttService.publish(context, MqttTopics.SENSOR_CALIBRATION_TOPIC, queryJsonAll.toString())

        val targetDevName = detectedSensorDeviceName ?: "home"
        if (targetDevName != "all") {
            val queryJsonDev = JSONObject().apply {
                put("action", "query")
                put("query", true)
                put("name", targetDevName)
            }
            MqttService.publish(context, MqttTopics.SENSOR_CALIBRATION_TOPIC, queryJsonDev.toString())
        }

        // 启动 5 秒超时检测协程
        coroutineScope.launch {
            delay(5000)
            if (isReading) {
                isReading = false
                readStatusText = "⚠️ 读取超时 (5s 未收到响应，请检查设备是否在线)"
                readStatusColor = Color(0xFFD32F2F)
                Toast.makeText(context, "读取超时：未收到设备响应", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 进入页面时主动订阅主题并触发一次读取
    LaunchedEffect(Unit) {
        MqttService.subscribe(context, MqttTopics.SENSOR_CALIBRATION_STATUS_TOPIC)
        MqttService.subscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
        if (isMqttConnected) {
            queryCurrentCalibration()
        }
    }

    // 收到固件回传的最新标定参数时自动刷新
    LaunchedEffect(latestCalStatus) {
        latestCalStatus?.let { channels ->
            for (item in channels) {
                when (item.ch) {
                    1 -> {
                        ch1Enabled = item.enabled
                        ch1K = String.format("%.6f", item.k)
                        ch1C = item.c.toString()
                        ch1Shift = item.shift.toString()
                    }
                    2 -> {
                        ch2Enabled = item.enabled
                        ch2K = String.format("%.6f", item.k)
                        ch2C = item.c.toString()
                        ch2Shift = item.shift.toString()
                    }
                    3 -> {
                        ch3Enabled = item.enabled
                        ch3K = String.format("%.6f", item.k)
                        ch3C = item.c.toString()
                        ch3Shift = item.shift.toString()
                    }
                }
            }
            saveToPrefs()

            // 如果正在读取，则标记成功
            if (isReading) {
                isReading = false
                val devName = detectedSensorDeviceName ?: "home"
                readStatusText = "✅ 参数同步成功 (设备: $devName, ${channels.size} 通道)"
                readStatusColor = Color(0xFF2E7D32)
                Toast.makeText(context, "已同步设备 ($devName) 标定参数", Toast.LENGTH_SHORT).show()
            }

            // 如果正在等待单通道下发确认
            if (pendingConfirmChannel >= 0) {
                val chIdx = pendingConfirmChannel
                val chNum = chIdx + 1
                val confirmedItem = channels.find { it.ch == chNum }
                if (confirmedItem != null) {
                    pendingConfirmChannel = -1
                    Toast.makeText(context, "🎉 通道 $chNum 配置已被设备确认生效！", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 控制 ESP32 上报数据流
    fun toggleDataStream(enable: Boolean) {
        if (!isMqttConnected) {
            Toast.makeText(context, "MQTT 未连接", Toast.LENGTH_SHORT).show()
            return
        }
        val targetName = detectedSensorDeviceName ?: "home"
        val ctrlJson = JSONObject().apply {
            put("enable", enable)
            put("interval", 1)
            put("name", targetName)
        }
        MqttService.publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, ctrlJson.toString())
        isDataStreamActive = enable
        Toast.makeText(context, if (enable) "已开启 1秒实时数据流" else "已停止实时数据流", Toast.LENGTH_SHORT).show()
    }

    // 下发单通道标定配置并启动 5 秒确认等待
    fun sendSingleChannelCal(channelIdx: Int, enabled: Boolean, kStr: String, cStr: String, shiftStr: String) {
        if (!isMqttConnected) {
            Toast.makeText(context, "MQTT 未连接，无法下发配置", Toast.LENGTH_SHORT).show()
            return
        }

        val k = kStr.toFloatOrNull() ?: 1.0f
        val c = cStr.toIntOrNull() ?: 32768
        val shift = shiftStr.toIntOrNull() ?: 6

        val targetName = detectedSensorDeviceName ?: "home"
        val rootJson = JSONObject().apply {
            put("name", targetName)
        }

        val chArray = JSONArray()
        val chObj = JSONObject().apply {
            put("ch", channelIdx + 1) // 1-based 通道号 (1~3)
            put("k", k.toDouble())
            put("c", c)
            put("shift", shift)
            put("enabled", enabled)
        }
        chArray.put(chObj)
        rootJson.put("channels", chArray)

        pendingConfirmChannel = channelIdx
        targetConfirmK = k

        try {
            MqttService.publish(context, MqttTopics.SENSOR_CALIBRATION_TOPIC, rootJson.toString())
            saveToPrefs()
            Toast.makeText(context, "通道 ${channelIdx + 1} 配置已发送，等待设备确认...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            pendingConfirmChannel = -1
            Toast.makeText(context, "下发失败: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 5秒确认超时
        coroutineScope.launch {
            delay(5000)
            if (pendingConfirmChannel == channelIdx) {
                pendingConfirmChannel = -1
                Toast.makeText(context, "⚠️ 通道 ${channelIdx + 1} 设备确认超时 (未收到回传)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 一键置零 (去皮)
    fun sendTare(channelIdx: Int) {
        if (!isMqttConnected) {
            Toast.makeText(context, "MQTT 未连接", Toast.LENGTH_SHORT).show()
            return
        }

        val targetName = detectedSensorDeviceName ?: "home"
        val rootJson = JSONObject().apply {
            put("name", targetName)
        }

        val chArray = JSONArray()
        val chObj = JSONObject().apply {
            put("ch", channelIdx + 1)
            put("tare_current", true)
        }
        chArray.put(chObj)
        rootJson.put("channels", chArray)

        MqttService.publish(context, MqttTopics.SENSOR_CALIBRATION_TOPIC, rootJson.toString())
        Toast.makeText(context, "通道 ${channelIdx + 1} 置零指令已下发", Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = {
            saveToPrefs()
            onDismissRequest()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 1. 顶部紧凑固定标题栏（高度严格固定为 48dp / 横屏 40dp，彻底杜绝膨胀占用屏幕）
            Surface(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isLandscape) 40.dp else 48.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                saveToPrefs()
                                onDismissRequest()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text("✕", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "HX711 标定配置",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    // 右侧设备标识与连接状态指示胶囊
                    Surface(
                        color = Color.Black.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (isMqttConnected && latestCalStatus != null) Color(0xFF4CAF50)
                                        else if (isMqttConnected) Color(0xFFFFB300)
                                        else Color(0xFFF44336),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "节点: ${detectedSensorDeviceName ?: "home"}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // 2. 主体可滚动区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 顶部控制与状态工具栏（平放展示，整洁规整）
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 读取设备参数按钮
                            Button(
                                onClick = { queryCurrentCalibration() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp),
                                enabled = !isReading
                            ) {
                                Text(
                                    text = if (isReading) "⏳ 读取中..." else "🔄 读取参数",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            // 实时数据流控制按钮
                            Button(
                                onClick = { toggleDataStream(!isDataStreamActive) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isDataStreamActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                                    contentColor = if (isDataStreamActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text(
                                    text = if (isDataStreamActive) "⏹ 停止实时流" else "▶ 开启实时流",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // 状态反馈文本
                        readStatusText?.let { txt ->
                            Text(
                                text = txt,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = readStatusColor
                            )
                        }
                    }
                }

                // 通道 1 标定卡片
                ChannelCalibrationCard(
                    channelIdx = 0,
                    channelName = "通道 1 (Ch1)",
                    currentReadingRaw = rawSensorData?.getOrNull(0) ?: 0,
                    currentAdRaw = rawSensorAdData?.getOrNull(0)
                        ?: latestCalStatus?.find { it.ch == 1 }?.ad
                        ?: 0,
                    enabled = ch1Enabled,
                    onEnabledChange = { ch1Enabled = it },
                    kValue = ch1K,
                    onKChange = { ch1K = it },
                    cValue = ch1C,
                    onCChange = { ch1C = it },
                    shiftValue = ch1Shift,
                    onShiftChange = { ch1Shift = it },
                    isPendingConfirm = (pendingConfirmChannel == 0),
                    onTare = { sendTare(0) },
                    onSend = { sendSingleChannelCal(0, ch1Enabled, ch1K, ch1C, ch1Shift) }
                )

                // 通道 2 标定卡片
                ChannelCalibrationCard(
                    channelIdx = 1,
                    channelName = "通道 2 (Ch2)",
                    currentReadingRaw = rawSensorData?.getOrNull(1) ?: 0,
                    currentAdRaw = rawSensorAdData?.getOrNull(1)
                        ?: latestCalStatus?.find { it.ch == 2 }?.ad
                        ?: 0,
                    enabled = ch2Enabled,
                    onEnabledChange = { ch2Enabled = it },
                    kValue = ch2K,
                    onKChange = { ch2K = it },
                    cValue = ch2C,
                    onCChange = { ch2C = it },
                    shiftValue = ch2Shift,
                    onShiftChange = { ch2Shift = it },
                    isPendingConfirm = (pendingConfirmChannel == 1),
                    onTare = { sendTare(1) },
                    onSend = { sendSingleChannelCal(1, ch2Enabled, ch2K, ch2C, ch2Shift) }
                )

                // 通道 3 标定卡片
                ChannelCalibrationCard(
                    channelIdx = 2,
                    channelName = "通道 3 (Ch3)",
                    currentReadingRaw = rawSensorData?.getOrNull(2) ?: 0,
                    currentAdRaw = rawSensorAdData?.getOrNull(2)
                        ?: latestCalStatus?.find { it.ch == 3 }?.ad
                        ?: 0,
                    enabled = ch3Enabled,
                    onEnabledChange = { ch3Enabled = it },
                    kValue = ch3K,
                    onKChange = { ch3K = it },
                    cValue = ch3C,
                    onCChange = { ch3C = it },
                    shiftValue = ch3Shift,
                    onShiftChange = { ch3Shift = it },
                    isPendingConfirm = (pendingConfirmChannel == 2),
                    onTare = { sendTare(2) },
                    onSend = { sendSingleChannelCal(2, ch3Enabled, ch3K, ch3C, ch3Shift) }
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

/**
 * 单通道标定卡片组件
 */
@Composable
fun ChannelCalibrationCard(
    channelIdx: Int,
    channelName: String,
    currentReadingRaw: Int,
    currentAdRaw: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    kValue: String,
    onKChange: (String) -> Unit,
    cValue: String,
    onCChange: (String) -> Unit,
    shiftValue: String,
    onShiftChange: (String) -> Unit,
    isPendingConfirm: Boolean,
    onTare: () -> Unit,
    onSend: () -> Unit
) {
    var showCalHelper by remember { mutableStateOf(false) }
    var knownWeightInput by remember { mutableStateOf("500") }

    // 固件上报的力值（0.1g 单位）转换为克数
    val currentForceGrams = currentReadingRaw / 10.0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 标题栏与通道使能
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channelName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (enabled) "已启用" else "已停用",
                        color = if (enabled) Color(0xFF2E7D32) else Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            // 重新组织的【实时测量与去皮置零】仪表板（规整双列仪表 + 独立操作栏，彻底杜绝折行与挤压）
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 第一行：两列平分的读数仪表卡片（左：力值，右：原始 AD 码值）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 左卡片：测量力值
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "当前测量力值",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = String.format("%.1f g", currentForceGrams),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        // 右卡片：实时原始 AD 码值
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFFF9800).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "原始 AD 码值 (16-bit)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (currentAdRaw > 0) currentAdRaw.toString() else "--",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (currentAdRaw > 0) Color(0xFFD84315) else Color.Gray,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    // 第二行：操作行（左：当前基准零点 c 说明；右：去皮置零操作按钮）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "零点基准 c: $cValue",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )

                        Button(
                            onClick = onTare,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("一键去皮", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 零点参数 c（独立整行，大方宽敞）
            OutlinedTextField(
                value = cValue,
                onValueChange = onCChange,
                label = { Text("零点偏移 (c 值)") },
                placeholder = { Text("例如 32768") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // 比例系数 k 与 AD 移位 shift（并排展示）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = kValue,
                    onValueChange = onKChange,
                    label = { Text("比例系数 (k)") },
                    placeholder = { Text("1.0") },
                    modifier = Modifier.weight(1.2f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = shiftValue,
                    onValueChange = onShiftChange,
                    label = { Text("移位 (shift)") },
                    placeholder = { Text("6") },
                    modifier = Modifier.weight(0.8f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // 砝码标定换算助手
            TextButton(
                onClick = { showCalHelper = !showCalHelper },
                contentPadding = PaddingValues(vertical = 2.dp, horizontal = 0.dp)
            ) {
                Text(
                    text = if (showCalHelper) "▲ 收起砝码换算助手" else "▼ 展开砝码标定换算助手 (已知砝码克数换算 k)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (showCalHelper) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. 先点击【一键置零/去皮】使空载读数为 0.0g", fontSize = 12.sp)
                        Text("2. 秤台放上已知重量砝码，输入实际克数并换算：", fontSize = 12.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = knownWeightInput,
                                onValueChange = { knownWeightInput = it },
                                label = { Text("已知砝码重量(g)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            Button(
                                onClick = {
                                    val knownGrams = knownWeightInput.toFloatOrNull()
                                    val currentK = kValue.toFloatOrNull() ?: 1.0f
                                    if (knownGrams != null && knownGrams > 0 && currentForceGrams > 0.05f) {
                                        val newK = currentK * (knownGrams / currentForceGrams)
                                        onKChange(String.format("%.6f", newK))
                                        showCalHelper = false
                                    }
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("换算并应用 k", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 下发配置主按钮：统一 MaterialTheme 主色，带等待确认状态提示
            Button(
                onClick = onSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPendingConfirm) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = !isPendingConfirm
            ) {
                Text(
                    text = if (isPendingConfirm) "⏳ 正在等待设备确认生效..." else "下发此通道配置",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
