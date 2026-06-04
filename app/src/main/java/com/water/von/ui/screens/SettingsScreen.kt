package com.water.von.ui.screens

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.water.von.ui.viewmodel.ParametersViewModel

/**
 * 采样时序与水泵参数设置页 SettingsScreen
 * 针对通道 1、通道 2、通道 3 分组显示 expected_duration 滑块和 pump_work_time 滑块
 */
@Composable
fun SettingsScreen(viewModel: ParametersViewModel = viewModel()) {
    val duration1 by viewModel.duration1.collectAsState()
    val pumpTime1 by viewModel.pumpTime1.collectAsState()

    val duration2 by viewModel.duration2.collectAsState()
    val pumpTime2 by viewModel.pumpTime2.collectAsState()

    val duration3 by viewModel.duration3.collectAsState()
    val pumpTime3 by viewModel.pumpTime3.collectAsState()

    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "各通道时序控制设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 通道 1 独立卡片
            ChannelConfigCard(
                channelId = 1,
                badgeColor = Color(0xFF2196F3),
                duration = duration1,
                pumpTime = pumpTime1,
                onDurationChange = { viewModel.updateDuration1(it) },
                onPumpTimeChange = { viewModel.updatePumpTime1(it) },
                onSyncClick = {
                    val success = viewModel.syncChannelParameters(1)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (success) "通道 1 配置已同步发送至远端树莓派！" else "同步失败：请检查服务器连接是否建立。"
                        )
                    }
                }
            )

            // 通道 2 独立卡片
            ChannelConfigCard(
                channelId = 2,
                badgeColor = Color(0xFF4CAF50),
                duration = duration2,
                pumpTime = pumpTime2,
                onDurationChange = { viewModel.updateDuration2(it) },
                onPumpTimeChange = { viewModel.updatePumpTime2(it) },
                onSyncClick = {
                    val success = viewModel.syncChannelParameters(2)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (success) "通道 2 配置已同步发送至远端树莓派！" else "同步失败：请检查服务器连接是否建立。"
                        )
                    }
                }
            )

            // 通道 3 独立卡片
            ChannelConfigCard(
                channelId = 3,
                badgeColor = Color(0xFFFF9800),
                duration = duration3,
                pumpTime = pumpTime3,
                onDurationChange = { viewModel.updateDuration3(it) },
                onPumpTimeChange = { viewModel.updatePumpTime3(it) },
                onSyncClick = {
                    val success = viewModel.syncChannelParameters(3)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (success) "通道 3 配置已同步发送至远端树莓派！" else "同步失败：请检查服务器连接是否建立。"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(60.dp)) // 给 Snackbar 留出足够间距
        }

        // SnackBar 显示容器
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// 辅助协程启动器
private fun CoroutineScope.launch(block: suspend () -> Unit) {
    this.launch(block = { block() })
}

@Composable
fun ChannelConfigCard(
    channelId: Int,
    badgeColor: Color,
    duration: Float,
    pumpTime: Float,
    onDurationChange: (Float) -> Unit,
    onPumpTimeChange: (Float) -> Unit,
    onSyncClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 卡片头部
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "C$channelId", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "通道 $channelId 采样时序",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onSyncClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = badgeColor)
                ) {
                    Text("同步远端", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Divider()

            // 1. 预期时长 Slider + TextField (单位：分钟)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "预期采样总时间 (expected_duration)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(text = "${duration.toInt()} 分钟", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = badgeColor)
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Slider(
                        value = duration,
                        onValueChange = { onDurationChange(it) },
                        valueRange = 1f..240f,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = duration.toInt().toString(),
                        onValueChange = { 
                            it.toFloatOrNull()?.let { v -> if (v in 1f..240f) onDurationChange(v) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(70.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(4.dp)
                    )
                }
            }

            // 2. 泵启动时间 Slider + TextField (单位：秒)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "水泵运行时间 (pump_work_time)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(text = "${pumpTime.toInt()} 秒", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = badgeColor)
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Slider(
                        value = pumpTime,
                        onValueChange = { onPumpTimeChange(it) },
                        valueRange = 1f..300f,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = pumpTime.toInt().toString(),
                        onValueChange = { 
                            it.toFloatOrNull()?.let { v -> if (v in 1f..300f) onPumpTimeChange(v) }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(70.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(4.dp)
                    )
                }
            }
        }
    }
}
