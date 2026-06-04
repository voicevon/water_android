package com.water.von.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: ParametersViewModel = viewModel()) {
    val duration1 by viewModel.duration1.collectAsState()
    val pumpTime1 by viewModel.pumpTime1.collectAsState()

    val duration2 by viewModel.duration2.collectAsState()
    val pumpTime2 by viewModel.pumpTime2.collectAsState()

    val duration3 by viewModel.duration3.collectAsState()
    val pumpTime3 by viewModel.pumpTime3.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("药液卸载时间", "水泵运行时间")

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { 
                            Text(
                                text = title,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal
                            ) 
                        }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedTabIndex == 0) {
                    // 药液卸载时间 - 分钟
                    CompactParameterCard(
                        channelName = "次氯酸钠",
                        badgeColor = Color(0xFF2196F3),
                        title = "药液卸载时间",
                        value = duration1,
                        unit = "分钟",
                        valueRange = 0f..180f,
                        onValueChange = { viewModel.updateDuration1(it) }
                    )
                    CompactParameterCard(
                        channelName = "碳源",
                        badgeColor = Color(0xFF4CAF50),
                        title = "药液卸载时间",
                        value = duration2,
                        unit = "分钟",
                        valueRange = 0f..180f,
                        onValueChange = { viewModel.updateDuration2(it) }
                    )
                    CompactParameterCard(
                        channelName = "铁盐",
                        badgeColor = Color(0xFFFF9800),
                        title = "药液卸载时间",
                        value = duration3,
                        unit = "分钟",
                        valueRange = 0f..180f,
                        onValueChange = { viewModel.updateDuration3(it) }
                    )
                } else {
                    // 水泵运行时间 - 秒
                    CompactParameterCard(
                        channelName = "次氯酸钠",
                        badgeColor = Color(0xFF2196F3),
                        title = "水泵运行时间",
                        value = pumpTime1,
                        unit = "秒",
                        valueRange = 0f..60f,
                        onValueChange = { viewModel.updatePumpTime1(it) }
                    )
                    CompactParameterCard(
                        channelName = "碳源",
                        badgeColor = Color(0xFF4CAF50),
                        title = "水泵运行时间",
                        value = pumpTime2,
                        unit = "秒",
                        valueRange = 0f..60f,
                        onValueChange = { viewModel.updatePumpTime2(it) }
                    )
                    CompactParameterCard(
                        channelName = "铁盐",
                        badgeColor = Color(0xFFFF9800),
                        title = "水泵运行时间",
                        value = pumpTime3,
                        unit = "秒",
                        valueRange = 0f..60f,
                        onValueChange = { viewModel.updatePumpTime3(it) }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                
                Button(
                    onClick = {
                        scope.launch {
                            val success = viewModel.syncAllParameters()
                            snackbarHostState.showSnackbar(if (success) "保存成功！" else "保存失败！")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保存", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CompactParameterCard(
    channelName: String,
    badgeColor: Color,
    title: String,
    value: Float,
    unit: String,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = channelName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Text(
                    text = "${value.toInt()} $unit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = value,
                onValueChange = { onValueChange(it) },
                valueRange = valueRange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
