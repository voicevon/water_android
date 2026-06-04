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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    val brokerUrl by viewModel.brokerUrlFlow.collectAsState()

    val context = LocalContext.current
    var displayFile by remember { mutableStateOf<File?>(null) }
    
    LaunchedEffect(Unit) {
        viewModel.refreshBrokerUrl()
    }

    LaunchedEffect(photoPath) {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val imagesDir = File(baseDir, "images")
        if (photoPath != null) {
            val imgFile = File(imagesDir, photoPath!!.substringAfter("images/"))
            if (imgFile.exists()) displayFile = imgFile
        } else {
            val latestFile = imagesDir.listFiles { _, name -> name.startsWith("IMG_") && name.endsWith(".jpg") }
                ?.maxByOrNull { it.lastModified() }
            displayFile = latestFile
        }
    }

    val scrollState = rememberScrollState()
    var showImagePreview by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isConnected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "云端未连接",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "控制器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val isOffline = systemStatus.equals("offline", ignoreCase = true)
                    val statusText = if (isOffline) "不在线" else "在线"
                    val statusColor = if (isOffline) Color.Red else Color.Green

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor
                        )
                    }
                }
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
                if (displayFile != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(displayFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = "污水监测图像快照",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showImagePreview = true },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "污水监测图像快照 (回传时间: ${displayFile!!.name.substringAfter("IMG_").substringBefore(".jpg")})",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                } else {
                    PhotoPlaceholder()
                }
            }
        }
    }

    if (showImagePreview && viewModel.latestPhotoPath.value != null) {
        val file = displayFile
        if (file != null) {
            Dialog(
                onDismissRequest = { showImagePreview = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    var scale by remember { mutableStateOf(1f) }
                    var offsetX by remember { mutableStateOf(0f) }
                    var offsetY by remember { mutableStateOf(0f) }

                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    if (scale > 1f) {
                                        val maxOffset = (scale - 1) * 1000f
                                        offsetX = (offsetX + pan.x).coerceIn(-maxOffset, maxOffset)
                                        offsetY = (offsetY + pan.y).coerceIn(-maxOffset, maxOffset)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            ),
                        contentScale = ContentScale.Fit
                    )

                    IconButton(
                        onClick = { showImagePreview = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Text("✖", color = Color.White, fontSize = 24.sp)
                    }
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
