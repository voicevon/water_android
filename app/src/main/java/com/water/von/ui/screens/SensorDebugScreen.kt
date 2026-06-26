package com.water.von.ui.screens

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import android.Manifest
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
    var isMqttStarted by remember { mutableStateOf(false) }
    val isMqttConnected by MqttService.isConnected.collectAsState()
    
    var prefixName by remember { mutableStateOf("dongzhan") }
    
    val dataPointsAll = remember { Array(4) { mutableStateListOf<SensorDataPoint>() } }
    var latestPoint by remember { mutableStateOf(SensorDataPoint(0, 0, 0, 0)) }
    var packetCount by remember { mutableStateOf(0) }
    
    var selectedChannel by remember { mutableStateOf(0) }
    val dataProcessors = remember { Array(4) { DataProcessor() } }
    var lastSeqNum by remember { mutableStateOf(-1) }
    
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val maxPoints = remember(configuration.screenWidthDp, density) {
        (configuration.screenWidthDp * density).toInt().coerceAtLeast(100)
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
        packetCount = 0
        lastSeqNum = -1
        for (i in 0 until 4) {
            dataPointsAll[i].clear()
        }
        latestPoint = SensorDataPoint(0, 0, 0, 0)
        
        if (debugMode != "MQTT" && isMqttStarted) {
            isMqttStarted = false
        }
    }

    LaunchedEffect(isMqttStarted, prefixName) {
        if (isMqttStarted && debugMode == "MQTT") {
            packetCount = 0
            lastSeqNum = -1
            for (i in 0 until 4) {
                dataPointsAll[i].clear()
            }
            latestPoint = SensorDataPoint(0, 0, 0, 0)
            
            MqttService.activeSensorPrefix = prefixName
            Toast.makeText(context, "开始 MQTT 调试: $prefixName", Toast.LENGTH_SHORT).show()
            MqttService.subscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
            MqttService.publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, prefixName)
            
            try {
                MqttService.latestSensorRawData.collect { data ->
                    if (data != null && data.size >= 4) {
                        packetCount++
                        val ch0 = data[0]
                        val ch1 = data[1]
                        val ch2 = data[2]
                        val ch3 = data[3]
                        
                        // UI左起 Ch4(对应物理ch3), Ch3(物理ch2), Ch2(物理ch1), Ch1(物理ch0)
                        val physicalChannels = arrayOf(ch3, ch2, ch1, ch0)
                        
                        for (i in 0 until 4) {
                            val rawValue = physicalChannels[i]
                            val filteredValue = dataProcessors[i].pushRaw(rawValue)
                            val baseline = dataProcessors[i].pushBaseline(filteredValue)
                            val threshold = baseline - DataProcessor.THRESHOLD_OFFSET
                            
                            val newPoint = SensorDataPoint(rawValue, filteredValue, baseline, threshold)
                            
                            dataPointsAll[i].add(newPoint)
                            if (dataPointsAll[i].size > maxPoints) {
                                dataPointsAll[i].removeAt(0)
                            }
                            
                            if (i == selectedChannel) {
                                latestPoint = newPoint
                            }
                        }
                    }
                }
            } finally {
                // 当调试结束或配置改变时，自动发送 stop 命令并取消订阅旧的 topic
                MqttService.publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, "stop")
                MqttService.unsubscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
                MqttService.activeSensorPrefix = null
            }
        }
    }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    DisposableEffect(hasPermissions, debugMode) {
        var scanner: BluetoothLeScanner? = null
        var scanCallback: ScanCallback? = null

        if (hasPermissions && debugMode == "BLE") {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter: BluetoothAdapter? = bluetoothManager.adapter
            
            if (adapter != null && adapter.isEnabled) {
                scanner = adapter.bluetoothLeScanner
                if (scanner != null) {
                    val filter = ScanFilter.Builder()
                        // 移除 manufacturer data 过滤，避免部分安卓系统强校验长度导致静默丢弃
                        .build()

                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()

                    scanCallback = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult?) {
                            super.onScanResult(callbackType, result)
                            result?.scanRecord?.getManufacturerSpecificData(0xFFFF)?.let { data ->
                                mainHandler.post {
                                    if (data.size >= 8) {
                                        val ch0 = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                                        val ch1 = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
                                        val ch2 = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
                                        val ch3 = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                                        
                                        // 使用序列号 (seq_num) 来判断去重，抛弃相同的数据包
                                        val seqNum = if (data.size > 8) data[8].toInt() and 0xFF else -1
                                        
                                        if (seqNum == -1 || seqNum != lastSeqNum) {
                                            lastSeqNum = seqNum
                                            packetCount++
                                            
                                            // UI左起 Ch4(对应物理ch3), Ch3(物理ch2), Ch2(物理ch1), Ch1(物理ch0)
                                            val physicalChannels = arrayOf(ch3, ch2, ch1, ch0) 
                                            
                                            for (i in 0 until 4) {
                                                val rawValue = physicalChannels[i]
                                                val filteredValue = dataProcessors[i].pushRaw(rawValue)
                                                val baseline = dataProcessors[i].pushBaseline(filteredValue)
                                                val threshold = baseline - DataProcessor.THRESHOLD_OFFSET
                                                
                                                val newPoint = SensorDataPoint(rawValue, filteredValue, baseline, threshold)
                                                
                                                dataPointsAll[i].add(newPoint)
                                                if (dataPointsAll[i].size > maxPoints) {
                                                    dataPointsAll[i].removeAt(0)
                                                }
                                                
                                                if (i == selectedChannel) {
                                                    latestPoint = newPoint
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    try {
                        scanner.startScan(listOf(filter), settings, scanCallback)
                        Log.d("SensorDebug", "Started BLE Scan")
                    } catch (e: SecurityException) {
                        Log.e("SensorDebug", "Permission denied for startScan", e)
                    }
                }
            }
        }

        onDispose {
            try {
                if (scanner != null && scanCallback != null && hasPermissions) {
                    scanner.stopScan(scanCallback)
                    Log.d("SensorDebug", "Stopped BLE Scan")
                }
            } catch (e: SecurityException) {
                Log.e("SensorDebug", "Permission denied for stopScan", e)
            }
            if (debugMode == "MQTT" && isMqttStarted) {
                MqttService.publish(context, MqttTopics.SENSOR_CONTROL_TOPIC, "stop")
                MqttService.unsubscribe(context, MqttTopics.SENSOR_STATUS_TOPIC)
                MqttService.activeSensorPrefix = null
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
                        text = { Text("BLE\n广播", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    )
                    Tab(
                        selected = debugMode == "MQTT",
                        onClick = { debugMode = "MQTT" },
                        text = { Text("MQTT\n网络", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
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
                        // Real-time Dashboard
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (debugMode == "MQTT") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                color = if (isMqttConnected) Color(0xFF4CAF50) else Color.Red,
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isMqttConnected) "MQTT 已连接" else "MQTT 已断开",
                                        fontSize = 12.sp,
                                        color = if (isMqttConnected) Color(0xFF4CAF50) else Color.Red,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                            Text(
                                text = "RX: $packetCount",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (debugMode == "MQTT") {
                            Button(
                                onClick = { 
                                    // 每次开始调试前，重新从全局配置加载最新的设备英文名称，确保立即生效
                                    if (!isMqttStarted) {
                                        val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                                        prefixName = sp.getString("prefix_name", "dongzhan") ?: "dongzhan"
                                    }
                                    isMqttStarted = !isMqttStarted 
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
                            listOf("Ch4", "Ch3", "Ch2", "Ch1").forEachIndexed { index, name ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selectedChannel == index,
                                        onClick = { 
                                            selectedChannel = index
                                            // 切换通道时直接从当前通道最新的数据点更新面板
                                            latestPoint = dataPointsAll[index].lastOrNull() ?: SensorDataPoint(0, 0, 0, 0)
                                        },
                                        modifier = Modifier.padding(end = 0.dp).size(24.dp)
                                    )
                                    Text(
                                        name, 
                                        fontWeight = if (selectedChannel == index) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
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

                        // Historical Chart
                        Text(
                            text = "历史曲线 (近 $maxPoints 次)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 16.dp)
                                .background(Color.White)
                        ) {
                            if (dataPointsAll[selectedChannel].isEmpty()) {
                                Text("等待数据传入...", modifier = Modifier.align(Alignment.Center))
                            } else {
                                MultiLineChart(
                                    dataPoints = dataPointsAll[selectedChannel],
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
