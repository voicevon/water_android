package com.water.von.ui.main

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.water.von.ui.screens.ConfigScreen
import com.water.von.ui.screens.LogsScreen
import com.water.von.ui.screens.MonitorScreen
import com.water.von.ui.screens.SettingsScreen

/**
 * 主容器页面 MainScreen
 * 整合底部导航栏，控制 4 个核心功能页的视图切换
 */
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
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
                    icon = { Text("📂", style = MaterialTheme.typography.titleLarge) },
                    label = { Text("日志", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⚙️", style = MaterialTheme.typography.titleLarge) },
                    label = { Text("配置", fontWeight = FontWeight.Bold) }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Text("🎛️", style = MaterialTheme.typography.titleLarge) },
                    label = { Text("参数", fontWeight = FontWeight.Bold) }
                )
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (selectedTab) {
                0 -> MonitorScreen()
                1 -> LogsScreen()
                2 -> ConfigScreen()
                3 -> SettingsScreen()
            }
        }
    }
}
