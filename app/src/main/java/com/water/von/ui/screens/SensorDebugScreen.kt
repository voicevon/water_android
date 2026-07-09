package com.water.von.ui.screens

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import android.Manifest
import android.content.pm.ActivityInfo
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
// 移除不存在的 LineChart 导入
import com.water.von.ui.components.MultiLineChart
import com.water.von.ui.components.SensorDataPoint
import com.water.von.utils.DataProcessor
import com.water.von.data.SensorChannel
import com.water.von.data.SensorState
import androidx.compose.runtime.collectAsState
import com.water.von.service.MqttService
import com.water.von.utils.MqttTopics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDebugScreen(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE) }
    
    var hasPermissions by remember { mutableStateOf(false) }
    var debugMode by remember { mutableStateOf("BLE") } // "BLE" or "MQTT"
    
    val isMqttStarted by MqttService.isMqttDebuggingActive.collectAsState()
    val activeInterval by MqttService.mqttDebugInterval.collectAsState()
    val isMqttConnected by MqttService.isConnected.collectAsState()
    val isBleScanning by MqttService.isBleScanning.collectAsState()
    
    var prefixName by remember { mutableStateOf("home") }
    var selectedInterval by remember { mutableStateOf(sp.getInt("mqtt_debug_interval", 1)) }
    
    // 当 MQTT 调试处于启动状态时，强制界面时间显示与 Service 的真实运行时间同步，防止跳变
    LaunchedEffect(isMqttStarted, activeInterval) {
        if (isMqttStarted) {
            selectedInterval = activeInterval
        }
    }

    
    // BLE 模式的全局数据状态绑定
    val bleDataSensor1 by MqttService.debugDataPointsBle[0].collectAsState()
    val bleDataSensor2 by MqttService.debugDataPointsBle[1].collectAsState()
    val bleDataSensor3 by MqttService.debugDataPointsBle[2].collectAsState()
    val bleDataAll = remember(bleDataSensor1, bleDataSensor2, bleDataSensor3) {
        arrayOf(bleDataSensor1, bleDataSensor2, bleDataSensor3)
    }
    val packetCountBle by MqttService.debugPacketCountBle.collectAsState()
    
    // MQTT 模式的全局数据状态绑定
    val packetCountMqtt by MqttService.debugPacketCount.collectAsState()
    
    var selectedChannel by remember { mutableStateOf(0) }
    
    var currentTimeString by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        while (true) {
            currentTimeString = sdf.format(java.util.Date())
            kotlinx.coroutines.delay(5000L)
        }
    }
    
    val maxPoints = 1020

    // 以 collectAsState 响应式订阅 debugDataPoints (MutableStateFlow)
    val mqttDataSensor1 by MqttService.debugDataPoints[0].collectAsState()
    val mqttDataSensor2 by MqttService.debugDataPoints[1].collectAsState()
    val mqttDataSensor3 by MqttService.debugDataPoints[2].collectAsState()
    val mqttDataAll = remember(mqttDataSensor1, mqttDataSensor2, mqttDataSensor3) {
        arrayOf(mqttDataSensor1, mqttDataSensor2, mqttDataSensor3)
    }

    val latestPoint = if (debugMode == "MQTT") {
        mqttDataAll[selectedChannel].lastOrNull() ?: SensorDataPoint(0, 0, 0, 0)
    } else {
        bleDataAll[selectedChannel].lastOrNull() ?: SensorDataPoint(0, 0, 0, 0)
    }

    val currentPoints = if (debugMode == "MQTT") {
        mqttDataAll[selectedChannel]
    } else {
        bleDataAll[selectedChannel]
    }

    val rxCount = if (debugMode == "MQTT") {
        packetCountMqtt
    } else {
        packetCountBle
    }

    val useGps = remember { sp.getBoolean("use_gps_positioning", true) }

    val requiredPermissions = remember(useGps) {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (useGps) {
                list.add(Manifest.permission.ACCESS_FINE_LOCATION)
                list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        } else {
            list.add(Manifest.permission.BLUETOOTH)
            list.add(Manifest.permission.BLUETOOTH_ADMIN)
            if (useGps) {
                list.add(Manifest.permission.ACCESS_FINE_LOCATION)
                list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.entries.all { it.value }
    }

    LaunchedEffect(requiredPermissions) {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            hasPermissions = true
        }
        
        prefixName = sp.getString("prefix_name", "home") ?: "home"
    }

    // 前台防休眠机制
    val activity = context as? android.app.Activity
    DisposableEffect(activity) {
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(hasPermissions, debugMode) {
        val modeOnStart = debugMode
        if (hasPermissions && modeOnStart == "BLE") {
            MqttService.startBleScan(context)
            Log.d("SensorDebug", "Sent start BLE Scan Intent to service")
        }

        onDispose {
            if (modeOnStart == "BLE") {
                MqttService.stopBleScan(context)
                Log.d("SensorDebug", "Sent stop BLE Scan Intent to service")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "传感器调试",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Text("❮", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                    },
                    actions = {
                        Text(
                            text = currentTimeString,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 16.dp),
                            fontWeight = FontWeight.Medium
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )

                // 包含 BLE广播、MQTT网络、参数 三个选项卡
                PrimaryTabRow(
                    selectedTabIndex = when (debugMode) {
                        "BLE" -> 0
                        "MQTT" -> 1
                        else -> 2
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = debugMode == "BLE",
                        onClick = { debugMode = "BLE" },
                        text = { Text("BLE", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    )
                    Tab(
                        selected = debugMode == "MQTT",
                        onClick = { debugMode = "MQTT" },
                        text = { Text("MQTT", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    )
                    Tab(
                        selected = debugMode == "参数",
                        onClick = { debugMode = "参数" },
                        text = { Text("参数", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    )
                }

                if (debugMode == "BLE" && !hasPermissions) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("等待蓝牙和定位权限授权...")
                    }
                } else if (debugMode == "参数") {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ParametersPanel(context, sp)
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp)
                            .verticalScroll(scrollState)
                    ) {
                        // Channel Selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("#1", "#2", "#3").forEachIndexed { index, name ->
                                val isWaterDetected = if (debugMode == "MQTT") {
                                    mqttDataAll[index].lastOrNull()?.hasWaterRemote == true
                                } else {
                                    bleDataAll[index].lastOrNull()?.hasWaterRemote == true
                                }
                                val dotColor = if (isWaterDetected) Color.Red else Color(0xFF4CAF50)

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedChannel == index,
                                        onClick = { 
                                            selectedChannel = index
                                        },
                                        modifier = Modifier.padding(end = 0.dp).size(24.dp)
                                    )
                                    Text(
                                        name,
                                        fontWeight = if (selectedChannel == index) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp,
                                        color = dotColor,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }

                        val activeAlarms by MqttService.activeAlarmsState.collectAsState()
                        if (activeAlarms.isNotEmpty()) {
                            Button(
                                onClick = { MqttService.clearAlarms(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Text("消除警报 (${activeAlarms.sorted().joinToString("，") { "传感器$it" }})", fontWeight = FontWeight.Bold)
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                ChannelValue("原始值", latestPoint.ch0, Color(0xFFD4AF37)) // Dark Yellow / Gold
                                ChannelValue("滤波值", latestPoint.ch1, Color(0xFF4CAF50)) // Green
                                ChannelValue("基准线", latestPoint.ch2, Color(0xFF2196F3)) // Blue
                                ChannelValue("触发阈值", latestPoint.ch3, Color.Red)
                            }
                        }

                        // 状态、有效数据量与接收计数器合并为一行，各指标采用两行垂直排列
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. 本地状态
                            val localColor = if (latestPoint.hasWater) Color.Red else Color(0xFF4CAF50)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(localColor, shape = androidx.compose.foundation.shape.CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "本地",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (latestPoint.hasWater) "有水" else "无水",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = localColor
                                )
                            }

                            // 2. 远程状态
                            val remoteColor = if (latestPoint.hasWaterRemote) Color.Red else Color(0xFF4CAF50)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(remoteColor, shape = androidx.compose.foundation.shape.CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "远程",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (latestPoint.hasWaterRemote) "有水" else "无水",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = remoteColor
                                )
                            }

                            // 3. 窗口宽度 / 数据量
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "数据",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${currentPoints.size} / $maxPoints",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }

                            // 4. RX 计数器
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "RX",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$rxCount",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(330.dp)
                                .padding(bottom = 16.dp)
                                .background(Color.Black)
                        ) {
                            if (currentPoints.isEmpty()) {
                                Text("等待数据传入...", modifier = Modifier.align(Alignment.Center))
                            } else {
                                MultiLineChart(
                                    dataPoints = currentPoints,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // MQTT 专属的调试控制器，挪到折线图下方展示，以统一 BLE/MQTT 上部结构
                        if (debugMode == "MQTT") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("发布间隔：", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedInterval == 1,
                                        onClick = { 
                                            if (!isMqttStarted) {
                                                selectedInterval = 1 
                                                sp.edit().putInt("mqtt_debug_interval", 1).apply()
                                            }
                                        },
                                        enabled = !isMqttStarted
                                    )
                                    Text(
                                        "1秒", 
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable(enabled = !isMqttStarted) { 
                                            selectedInterval = 1 
                                            sp.edit().putInt("mqtt_debug_interval", 1).apply()
                                        },
                                        color = if (!isMqttStarted) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = selectedInterval == 60,
                                        onClick = { 
                                            if (!isMqttStarted) {
                                                selectedInterval = 60 
                                                sp.edit().putInt("mqtt_debug_interval", 60).apply()
                                            }
                                        },
                                        enabled = !isMqttStarted
                                    )
                                    Text(
                                        "60秒", 
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable(enabled = !isMqttStarted) { 
                                            selectedInterval = 60 
                                            sp.edit().putInt("mqtt_debug_interval", 60).apply()
                                        },
                                        color = if (!isMqttStarted) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                                    )
                                }
                            }

                            Button(
                                onClick = { 
                                    if (!isMqttStarted) {
                                        val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                                        prefixName = sp.getString("prefix_name", "home") ?: "home"
                                        MqttService.startMqttDebugging(context, prefixName, selectedInterval)
                                    } else {
                                        MqttService.stopMqttDebugging(context)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isMqttStarted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(if (isMqttStarted) "停止 MQTT 调试" else "开始 MQTT 调试", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { MqttService.clearHistory(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(120.dp)
                                .height(36.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("清空历史数据", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelValue(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontWeight = FontWeight.Bold, color = color, fontSize = 10.sp)
        Text(text = value.toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ParametersPanel(context: Context, sp: android.content.SharedPreferences) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "施密特触发器偏置值 (Offset) 设置",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        // 物理映射说明：
        // 通道 1 对应 MqttService 中的索引 0
        // 通道 2 对应 MqttService 中的索引 1
        // 通道 3 对应 MqttService 中的索引 2
        val channelMappings = listOf(
            Triple(1, 0, "传感器 1"),
            Triple(2, 1, "传感器 2"),
            Triple(3, 2, "传感器 3")
        )
        
        channelMappings.forEach { (uiNum, index, desc) ->
            var offsetValue by remember { 
                mutableStateOf(sp.getInt("channel_offset_$index", 50)) 
            }
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "通道 $uiNum",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "物理映射: $desc",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (offsetValue > 10) {
                                    val newValue = offsetValue - 5
                                    offsetValue = newValue
                                    MqttService.updateChannelOffset(context, index, newValue)
                                }
                            }
                        ) {
                            Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        
                        Text(
                            text = "$offsetValue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        
                        IconButton(
                            onClick = {
                                if (offsetValue < 200) {
                                    val newValue = offsetValue + 5
                                    offsetValue = newValue
                                    MqttService.updateChannelOffset(context, index, newValue)
                                }
                            }
                        ) {
                            Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
