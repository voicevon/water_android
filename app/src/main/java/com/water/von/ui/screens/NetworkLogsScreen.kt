package com.water.von.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.water.von.ui.viewmodel.ConfigViewModel

/**
 * 独立的网络诊断日志页面 NetworkLogsScreen
 * 显示底层的 MQTT 通信和重连日志，具备纯黑命令行控制台的滚动交互质感
 */
@Composable
fun NetworkLogsScreen(viewModel: ConfigViewModel = viewModel()) {
    val consoleLogs by viewModel.consoleLogs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "网络底层诊断日志",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // 铺满剩余的高度空间，使日志显示更广
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E)) // 纯黑质感背景
                .padding(12.dp)
        ) {
            val consoleScrollState = rememberScrollState()
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(consoleScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (consoleLogs.isEmpty()) {
                    Text(
                        text = "控制台空闲，等待日志...",
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                } else {
                    consoleLogs.forEach { log ->
                        Text(
                            text = log,
                            color = if (log.contains("连接成功")) {
                                Color(0xFF4CAF50) // 成功显示绿色
                            } else if (log.contains("失败") || log.contains("丢失") || log.contains("断开")) {
                                Color(0xFFF44336) // 失败/异常显示红色
                            } else {
                                Color(0xFFE0E0E0) // 默认信息显示浅灰色
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }
    }
}
