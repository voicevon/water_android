package com.water.von.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.von.ui.screens.ConfigScreen
import com.water.von.ui.screens.LogsScreen
import com.water.von.ui.screens.MonitorScreen
import com.water.von.MainActivity
import com.water.von.ui.screens.SensorDebugScreen
import com.water.von.ui.screens.NetworkLogsScreen
import com.water.von.ui.screens.StationSettingsScreen
import com.water.von.ui.screens.CameraSettingsScreen
import com.water.von.ui.screens.DeviceHubScreen

private const val TAB_MONITOR = 0
private const val TAB_DEVICE_HUB = 1
private const val TAB_LOGS = 2
private const val TAB_CONFIG = 3
private const val TAB_NETWORK_LOGS = 4
private const val TAB_STATION_SETTINGS = 5
private const val TAB_CAMERA_SETTINGS = 6

/**
 * 主容器页面 MainScreen
 * 整合底部/侧边导航栏，控制核心功能页之视图切换
 * 包含：监控主屏、硬件设备工作台、运行日志三大主干页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var selectedTab by remember { mutableStateOf(TAB_MONITOR) }
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showSensorDebugWindow by remember { mutableStateOf(MainActivity.showSensorDebugOnLaunch) }

    LaunchedEffect(MainActivity.showSensorDebugOnLaunch) {
        if (MainActivity.showSensorDebugOnLaunch) {
            showSensorDebugWindow = true
        }
    }

    val stationTitle by com.water.von.service.MqttService.stationChineseName.collectAsState()

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于本系统", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("水文水样监测采集系统 V2.0")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("用户单位：工厂之家")
                    Text("开发日期：2026年")
                    Text("技术支持：冯工")
                    Text("电话：13306400990")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    if (showSensorDebugWindow) {
        SensorDebugScreen(
            onDismissRequest = { 
                showSensorDebugWindow = false 
                MainActivity.showSensorDebugOnLaunch = false
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = if (isLandscape) Modifier.height(36.dp) else Modifier,
                title = { Text(stationTitle, fontWeight = FontWeight.Bold, fontSize = if (isLandscape) 14.sp else 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                windowInsets = if (isLandscape) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
                actions = {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = if (isLandscape) Modifier.size(36.dp) else Modifier
                    ) {
                        Text("⋮", fontSize = if (isLandscape) 18.sp else 24.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("🛠️ 硬件设备工作台") },
                            onClick = {
                                showMenu = false
                                selectedTab = TAB_DEVICE_HUB
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📈 传感器 AD 波形调试") },
                            onClick = {
                                showMenu = false
                                showSensorDebugWindow = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("📷 摄像头突变配置") },
                            onClick = {
                                showMenu = false
                                selectedTab = TAB_CAMERA_SETTINGS
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("⚙️ 站点本地配置") },
                            onClick = {
                                showMenu = false
                                selectedTab = TAB_STATION_SETTINGS
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("☁️ 云端网络配置") },
                            onClick = {
                                showMenu = false
                                selectedTab = TAB_CONFIG
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🌐 网络诊断日志") },
                            onClick = {
                                showMenu = false
                                selectedTab = TAB_NETWORK_LOGS
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("ℹ️ 关于本系统") },
                            onClick = {
                                showMenu = false
                                showAboutDialog = true
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!isLandscape) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == TAB_MONITOR,
                        onClick = { selectedTab = TAB_MONITOR },
                        icon = { Text("📊", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("监控", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == TAB_DEVICE_HUB,
                        onClick = { selectedTab = TAB_DEVICE_HUB },
                        icon = { Text("🛠️", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("工作台", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == TAB_LOGS,
                        onClick = { selectedTab = TAB_LOGS },
                        icon = { Text("📂", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("日志", fontWeight = FontWeight.Bold) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                )
        ) {
            if (isLandscape) {
                // 自定义极窄侧边导航栏，避开 56dp 宽度限制
                Surface(
                    modifier = Modifier
                        .width(48.dp)
                        .fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Tab 1: 监控
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = TAB_MONITOR }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📊",
                                fontSize = 18.sp,
                                color = if (selectedTab == TAB_MONITOR) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "监控",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == TAB_MONITOR) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Tab 2: 硬件工作台
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = TAB_DEVICE_HUB }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🛠️",
                                fontSize = 18.sp,
                                color = if (selectedTab == TAB_DEVICE_HUB) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "硬件",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == TAB_DEVICE_HUB) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Tab 3: 日志
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = TAB_LOGS }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📂",
                                fontSize = 18.sp,
                                color = if (selectedTab == TAB_LOGS) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "日志",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == TAB_LOGS) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(
                        start = if (isLandscape) 0.dp else innerPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                        end = innerPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
                    ),
                color = MaterialTheme.colorScheme.background
            ) {
                when (selectedTab) {
                    TAB_MONITOR -> MonitorScreen()
                    TAB_DEVICE_HUB -> DeviceHubScreen(
                        onOpenSensorDebug = { showSensorDebugWindow = true },
                        onOpenCameraSettings = { selectedTab = TAB_CAMERA_SETTINGS },
                        onOpenStationSettings = { selectedTab = TAB_STATION_SETTINGS }
                    )
                    TAB_LOGS -> LogsScreen()
                    TAB_CONFIG -> ConfigScreen()
                    TAB_NETWORK_LOGS -> NetworkLogsScreen()
                    TAB_STATION_SETTINGS -> StationSettingsScreen()
                    TAB_CAMERA_SETTINGS -> CameraSettingsScreen()
                }
            }
        }
    }
}
