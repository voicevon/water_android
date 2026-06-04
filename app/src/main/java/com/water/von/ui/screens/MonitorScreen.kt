package com.water.von.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.water.von.data.LogEntry
import com.water.von.ui.viewmodel.MonitorViewModel
import java.io.File

/**
 * 主监控页面 MonitorScreen
 * 呈现 MQTT 网络状态、诊断看板、三分道滚动日志以及最新污水抓拍照
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val isConnected by viewModel.isConnected.collectAsState()
    val systemStatus by viewModel.systemStatus.collectAsState()
    val systemInfo by viewModel.systemInfo.collectAsState()
    val log1 by viewModel.latestLogChannel1.collectAsState()
    val log2 by viewModel.latestLogChannel2.collectAsState()
    val log3 by viewModel.latestLogChannel3.collectAsState()
    val photoPath by viewModel.latestPhotoPath.collectAsState()

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. MQTT 连接状态栏卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "远程服务连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Broker: voicevon.vicp.io:1883",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 连接指示灯灯泡
                    val lightColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(lightColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConnected) "已连接" else "未连接",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2. 系统诊断看板 (pi_water/system/info)
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "树莓派设备诊断信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 解析 \n 三行数据
                val infoLines = systemInfo.split("\n")
                val deviceTime = infoLines.getOrNull(0) ?: "---"
                val bootTime = infoLines.getOrNull(1) ?: "---"
                val uptime = infoLines.getOrNull(2) ?: "---"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "设备当前时钟", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(text = deviceTime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "系统启动时间", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(text = bootTime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Text(text = "系统累计运行时间", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(text = uptime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "系统当前工作模式: ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(
                        text = systemStatus.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 3. 分通道日志流实时监视 (3卡片并行布局)
        Text(
            text = "通道动作流实时监视",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChannelLogCard(channelId = 1, logEntry = log1)
            ChannelLogCard(channelId = 2, logEntry = log2)
            ChannelLogCard(channelId = 3, logEntry = log3)
        }

        // 4. 污水抓拍照监视区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val file = photoPath?.let {
                    val baseDir = context.getExternalFilesDir(null)
                    val imgFile = File(baseDir, it.substringAfter("images/"))
                    if (imgFile.exists()) imgFile else null
                }

                if (file != null) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "污水监测图像快照",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "污水监测图像快照 (回传时间: ${file.name.substringAfter("IMG_").substringBefore(".jpg")})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    } else {
                        PhotoPlaceholder()
                    }
                } else {
                    PhotoPlaceholder()
                }
            }
        }
    }
}

@Composable
fun ChannelLogCard(channelId: Int, logEntry: LogEntry?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 炫彩通道圆形徽标
                val badgeColor = when (channelId) {
                    1 -> Color(0xFF2196F3)
                    2 -> Color(0xFF4CAF50)
                    else -> Color(0xFFFF9800)
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "C$channelId", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "通道 $channelId 最新事件",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )
                    Text(
                        text = logEntry?.message ?: "暂无运行消息",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (logEntry?.level == "ERROR") Color.Red else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            logEntry?.time?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun PhotoPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "📷 污水图像快照监视",
            style = MaterialTheme.typography.titleMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "现场水泵启动时，自动触发拍照并在此回传",
            style = MaterialTheme.typography.labelSmall,
            color = Color.LightGray
        )
    }
}
