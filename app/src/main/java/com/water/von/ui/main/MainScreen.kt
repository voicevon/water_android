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
import com.water.von.ui.screens.SettingsScreen

/**
 * 主容器页面 MainScreen
 * 整合底部导航栏，控制 4 个核心功能页的视图切换
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var selectedTab by remember { mutableStateOf(0) }
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("用户单位：济南东站污水厂")
                    Text("开发日期：2026年2月")
                    Text("技术支持：冯工")
                    Text("电话：13306400990")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("版本号：1.0")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = if (isLandscape) Modifier.height(36.dp) else Modifier,
                title = { Text("济南东站污水厂", fontWeight = FontWeight.Bold, fontSize = if (isLandscape) 14.sp else 20.sp) },
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
                            text = { Text("云端配置") },
                            onClick = {
                                showMenu = false
                                selectedTab = 3
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("关于") },
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
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Text("📊", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("监控", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Text("🎛️", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("设定", fontWeight = FontWeight.Bold) }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
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
                // 自定义极窄侧边导航栏，完全避开 Material 3 NavigationRail 的 56dp 宽度限制，让图标不受挤压且保持正常比例
                Surface(
                    modifier = Modifier
                        .width(44.dp)
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
                                .clickable { selectedTab = 0 }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📊",
                                fontSize = 20.sp,
                                color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "监控",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Tab 2: 设定
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = 1 }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🎛️",
                                fontSize = 20.sp,
                                color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "设定",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Tab 3: 日志
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTab = 2 }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📂",
                                fontSize = 20.sp,
                                color = if (selectedTab == 2) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "日志",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 2) MaterialTheme.colorScheme.primary else Color.Gray
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
                    0 -> MonitorScreen()
                    1 -> SettingsScreen()
                    2 -> LogsScreen()
                    3 -> ConfigScreen()
                }
            }
        }
    }
}
