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
// 移除不存在的 LineChart 导入
import com.water.von.ui.components.MultiLineChart
import com.water.von.ui.components.SensorDataPoint
import com.water.von.utils.DataProcessor
import androidx.compose.runtime.collectAsState
import com.water.von.service.MqttService
import com.water.von.utils.MqttTopics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorDebugScreen(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var debugMode by remember { mutableStateOf("BLE") } // "BLE" or "MQTT"
    
    val isMqttStarted by MqttService.isMqttDebuggingActive.collectAsState()
    val isMqttConnected by MqttService.isConnected.collectAsState()
    val isBleScanning by MqttService.isBleScanning.collectAsState()
    
    var prefixName by remember { mutableStateOf("dongzhan") }
    
    // BLE 模式的局部数据状态
    val dataPointsBle = remember { Array(4) { mutableStateListOf<SensorDataPoint>() } }
    var latestPointBle by remember { mutableStateOf(SensorDataPoint(0, 0, 0, 0)) }
    var packetCountBle by remember { mutableStateOf(0) }
    val dataProcessorsBle = remember { Array(4) { DataProcessor() } }
    
    // MQTT 模式的全局数据状态绑定
    val packetCountMqtt by MqttService.debugPacketCount.collectAsState()
    
    var selectedChannel by remember { mutableStateOf(0) }
    
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val maxPoints = remember(configuration.screenWidthDp, density) {
        (configuration.screenWidthDp * density).toInt().coerceAtLeast(100)
    }

    // 以 collectAsState 响应式订阅 debugDataPoints (MutableStateFlow)
    val mqttDataCh0 by MqttService.debugDataPoints[0].collectAsState()
    val mqttDataCh1 by MqttService.debugDataPoints[1].collectAsState()
    val mqttDataCh2 by MqttService.debugDataPoints[2].collectAsState()
    val mqttDataCh3 by MqttService.debugDataPoints[3].collectAsState()
    val mqttDataAll = remember(mqttDataCh0, mqttDataCh1, mqttDataCh2, mqttDataCh3) {
        arrayOf(mqttDataCh0, mqttDataCh1, mqttDataCh2, mqttDataCh3)
    }

    val latestPoint = if (debugMode == "MQTT") {
        mqttDataAll[selectedChannel].lastOrNull() ?: SensorDataPoint(0, 0, 0, 0)
    } else {
        latestPointBle
    }

    val currentPoints = if (debugMode == "MQTT") {
        mqttDataAll[selectedChannel]
    } else {
        dataPointsBle[selectedChannel]
    }

    val rxCount = if (debugMode == "MQTT") {
        packetCountMqtt
    } else {
        packetCountBle
    }

    val sp = remember { context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE) }
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
        
        prefixName = sp.getString("prefix_name", "dongzhan") ?: "dongzhan"
    }

    LaunchedEffect(debugMode) {
        if (debugMode == "BLE") {
            packetCountBle = 0
            for (i in 0 until 4) {
                dataPointsBle[i].clear()
            }
            latestPointBle = SensorDataPoint(0, 0, 0, 0)
        }
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



    // 独立处理 BLE 数据收集
    LaunchedEffect(debugMode) {
        if (debugMode == "BLE") {
            try {
                MqttService.latestSensorRawData.collect { data ->
                    if (data != null && data.size >= 4) {
                        packetCountBle++
                        val ch0 = data[0]
                        val ch1 = data[1]
                        val ch2 = data[2]
                        val ch3 = data[3]
                        
                        val physicalChannels = arrayOf(ch3, ch2, ch1, ch0)
                        
                        for (i in 0 until 4) {
                            val rawValue = physicalChannels[i]
                            val filteredValue = dataProcessorsBle[i].pushRaw(rawValue)
                            val baseline = dataProcessorsBle[i].pushBaseline(filteredValue)
                            val threshold = baseline - DataProcessor.THRESHOLD_OFFSET
                            
                            val newPoint = SensorDataPoint(rawValue, filteredValue, baseline, threshold)
                            
                            dataPointsBle[i].add(newPoint)
                            if (dataPointsBle[i].size > maxPoints) {
                                dataPointsBle[i].removeAt(0)
                            }
                            
                            if (i == selectedChannel) {
                                latestPointBle = newPoint
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SensorDebug", "Failed to collect BLE data", e)
            }
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
                            text = "传感器调试"
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Text("❮", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )

                // 仅保留 BLE广播 与 MQTT网络 两个选项卡，移除“选择节点”配置页面
                PrimaryTabRow(
                    selectedTabIndex = when (debugMode) {
                        "BLE" -> 0
                        else -> 1
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
                }

                if (debugMode == "BLE" && !hasPermissions) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("等待蓝牙和定位权限授权...")
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {


                        if (debugMode == "MQTT") {
                            Button(
                                onClick = { 
                                    if (!isMqttStarted) {
                                        val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                                        prefixName = sp.getString("prefix_name", "dongzhan") ?: "dongzhan"
                                        MqttService.startMqttDebugging(context, prefixName)
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
                        
                        // Channel Selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("#4", "#3", "#2", "#1").forEachIndexed { index, name ->
                                val isWaterDetected = if (debugMode == "MQTT") {
                                    mqttDataAll[index].lastOrNull()?.hasWater == true
                                } else {
                                    dataPointsBle[index].lastOrNull()?.hasWater == true
                                }
                                val dotColor = if (isWaterDetected) Color.Red else Color(0xFF4CAF50)

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedChannel == index,
                                        onClick = { 
                                            selectedChannel = index
                                            // 切换通道时直接从当前通道最新的数据点更新面板
                                            if (debugMode != "MQTT") {
                                                latestPointBle = dataPointsBle[index].lastOrNull() ?: SensorDataPoint(0, 0, 0, 0)
                                            }
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
                                Text("消除警报 (${activeAlarms.sorted().joinToString("，") { "通道$it" }})", fontWeight = FontWeight.Bold)
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

                        // 状态、有效数据量与接收计数器合并为一行
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. 水状态 (无水/有水)
                            val stateColor = if (latestPoint.hasWater) Color.Red else Color(0xFF4CAF50)
                            val stateLabel = if (latestPoint.hasWater) "有水" else "无水"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(stateColor, shape = androidx.compose.foundation.shape.CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stateLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = stateColor
                                )
                            }

                            // 2. 窗口宽度 / 数据量
                            Text(
                                text = "数据: ${currentPoints.size} / $maxPoints",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            // 3. RX 计数器
                            Text(
                                text = "RX: $rxCount",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
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
