package com.water.von.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * 重定义后的主监控页面 MonitorScreen
 * 呈现三段式核心监控：上部管道流、中部起伏折线步骤条、下部图像快照与网关诊断看板
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val systemStatus by viewModel.systemStatus.collectAsState()
    val systemInfo by viewModel.systemInfo.collectAsState()
    val log1 by viewModel.latestLogChannel1.collectAsState()
    val log2 by viewModel.latestLogChannel2.collectAsState()
    val log3 by viewModel.latestLogChannel3.collectAsState()
    val photoPath by viewModel.latestPhotoPath.collectAsState()

    val pipe1HasWater by viewModel.pipe1HasWater.collectAsState()
    val pipe2HasWater by viewModel.pipe2HasWater.collectAsState()
    val pipe3HasWater by viewModel.pipe3HasWater.collectAsState()
    val currentStatusIndex by viewModel.currentStatusIndex.collectAsState()

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
        // 1. 上部分：三个药液管道水流监视卡片（置顶第一栏，无网关连接条）
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PipeCard(
                name = "次氯酸钠",
                hasWater = pipe1HasWater,
                latestMsg = log1?.message ?: "暂无消息",
                onClick = { viewModel.setPipeHasWater(1, !pipe1HasWater) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PipeCard(
                name = "碳源",
                hasWater = pipe2HasWater,
                latestMsg = log2?.message ?: "暂无消息",
                onClick = { viewModel.setPipeHasWater(2, !pipe2HasWater) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            PipeCard(
                name = "铁盐",
                hasWater = pipe3HasWater,
                latestMsg = log3?.message ?: "暂无消息",
                onClick = { viewModel.setPipeHasWater(3, !pipe3HasWater) },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // 2. 中部分：10步采样流程起伏式步骤条
        StepProgressBar(
            currentIndex = currentStatusIndex,
            onStepClick = { index -> viewModel.setCurrentStatusIndex(index) }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 3. 下部分：污水图像快照监视区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
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
                        text = "物理照片回传 (回传时间: ${displayFile!!.name.substringAfter("IMG_").substringBefore(".jpg")})",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                } else {
                    PhotoPlaceholder()
                }
            }
        }

        // 4. 下部分：网关物理诊断看板
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
                        text = "物理网关诊断看板",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val isOffline = systemStatus.equals("offline", ignoreCase = true)
                    val statusText = if (isOffline) "柜体离线" else "柜体正常"
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
                        Text(text = "系统当前时钟", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(text = deviceTime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "控制器启动时间", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(text = bootTime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    Text(text = "累积稳定运行时间", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(text = uptime, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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

/**
 * 管道水流状态卡片组件 (去除了有水流/无水流汉字标识，完全由图形色彩表达)
 */
@Composable
fun PipeCard(
    name: String,
    hasWater: Boolean,
    latestMsg: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (hasWater) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val strokeColor = if (hasWater) MaterialTheme.colorScheme.primary else Color.Transparent
    val pipeColor = if (hasWater) Color(0xFF2196F3) else Color.Gray

    Card(
        modifier = modifier
            .clickable { onClick() }
            .padding(2.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = if (hasWater) BorderStroke(1.5.dp, strokeColor) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 水流/管道示意圆形图
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pipeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasWater) "💧" else "⚪",
                    fontSize = 22.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasWater) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * 10 步起伏式采样流程折线步骤条组件 (Wavy-height Step Line)
 */
@Composable
fun StepProgressBar(
    currentIndex: Int,
    onStepClick: (Int) -> Unit
) {
    val steps = listOf("空闲", "待稳", "取头样", "延时", "取中样", "延时", "取尾样", "延时", "排空", "结束")
    
    // Y 偏移量高度档次对应这 10 个状态 (Y值越小表示高度越高)
    // 0:等待(110dp), 1:准备(60dp), 2:头样(10dp), 3:等待(60dp), 4:中样(10dp), 5:等待(60dp), 6:尾样(10dp), 7:等待(60dp), 8:排空(10dp), 9:结束(110dp)
    val yOffsets = listOf(150.dp, 100.dp, 50.dp, 100.dp, 50.dp, 100.dp, 50.dp, 100.dp, 50.dp, 150.dp)

    val density = LocalDensity.current
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = Color.LightGray.copy(alpha = 0.5f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                val widthPx = constraints.maxWidth
                val widthDp = with(density) { widthPx.toDp() }
                
                val stepCount = steps.size
                val stepWidth = widthDp / stepCount
                val nodeRadius = 14.dp

                // 计算 10 个步骤圆心的绝对 DP 坐标，用于 Canvas 画线
                val points = steps.mapIndexed { index, _ ->
                    val x = stepWidth * index + stepWidth / 2
                    val y = yOffsets[index] + nodeRadius
                    Pair(x, y)
                }

                // 1. 绘制折线背景与高亮折线
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val path = Path()
                    points.forEachIndexed { index, pair ->
                        val xPx = pair.first.toPx()
                        val yPx = pair.second.toPx()
                        if (index == 0) {
                            path.moveTo(xPx, yPx)
                        } else {
                            path.lineTo(xPx, yPx)
                        }
                    }

                    // 绘制底图灰色折线
                    drawPath(
                        path = path,
                        color = inactiveColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // 绘制已完成/激活状态的高亮蓝色折线
                    if (currentIndex >= 0) {
                        val activePath = Path()
                        val activePointsCount = (currentIndex + 1).coerceAtMost(stepCount)
                        for (i in 0 until activePointsCount) {
                            val xPx = points[i].first.toPx()
                            val yPx = points[i].second.toPx()
                            if (i == 0) {
                                activePath.moveTo(xPx, yPx)
                            } else {
                                activePath.lineTo(xPx, yPx)
                            }
                        }
                        drawPath(
                            path = activePath,
                            color = activeColor,
                            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                // 2. 绘制 10 个起伏分布的节点
                steps.forEachIndexed { index, stepName ->
                    val isCompleted = index < currentIndex
                    val isActive = index == currentIndex

                    val nodeColor = when {
                        isActive -> activeColor
                        isCompleted -> MaterialTheme.colorScheme.secondary
                        else -> Color.Gray.copy(alpha = 0.5f)
                    }

                    val textColor = when {
                        isActive -> activeColor
                        isCompleted -> MaterialTheme.colorScheme.onSurface
                        else -> Color.Gray
                    }

                    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                    // X, Y 位移定位
                    val xPos = stepWidth * index + stepWidth / 2 - nodeRadius
                    val yPos = yOffsets[index]

                    Box(
                        modifier = Modifier
                            .offset(x = xPos, y = yPos)
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(nodeColor)
                            .clickable { onStepClick(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    // 将文字精确错落定位在节点正下方 (部分阶段放在上方，部分阶段放在下方)
                    val textWidth = 50.dp // 设定一个合理字宽用于居中
                    val textXPos = stepWidth * index + stepWidth / 2 - textWidth / 2
                    val textYPos = if (index % 2 == 0 && index != 0 && index != 9) {
                        yPos - 35.dp
                    } else {
                        yPos + 32.dp
                    }

                    Text(
                        text = stepName,
                        fontSize = 9.sp,
                        fontWeight = fontWeight,
                        color = textColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .offset(x = textXPos, y = textYPos)
                            .width(textWidth)
                    )
                }
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
            text = "现场采样泵启动时，自动触发回传照片",
            style = MaterialTheme.typography.labelSmall,
            color = Color.LightGray
        )
    }
}


