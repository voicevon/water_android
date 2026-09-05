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
 * 呈现三段式核心监控：上部管道流、中部起伏折线步骤条、下部图像快照
 */
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val systemStatus by viewModel.systemStatus.collectAsState()
    val systemInfo by viewModel.systemInfo.collectAsState()
    val log1 by viewModel.latestLogChannel1.collectAsState()
    val log2 by viewModel.latestLogChannel2.collectAsState()
    val log3 by viewModel.latestLogChannel3.collectAsState()
    val photoPath by viewModel.latestPhotoPath.collectAsState()
    val isTakingPhoto by viewModel.isTakingPhoto.collectAsState()

    val pipe1HasWater by viewModel.pipe1HasWater.collectAsState()
    val pipe2HasWater by viewModel.pipe2HasWater.collectAsState()
    val pipe3HasWater by viewModel.pipe3HasWater.collectAsState()
    val currentStatusIndex by viewModel.currentStatusIndex.collectAsState()
    val currentSensorId by viewModel.currentSensorId.collectAsState()

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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PipeCard(
                    name = "次氯酸钠",
                    hasWater = pipe1HasWater,
                    latestMsg = log1?.message ?: "暂无消息",
                    modifier = Modifier.fillMaxWidth()
                )
                if (currentSensorId == 1) {
                    TotalSamplingProgressBar(viewModel)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PipeCard(
                    name = "碳源",
                    hasWater = pipe2HasWater,
                    latestMsg = log2?.message ?: "暂无消息",
                    modifier = Modifier.fillMaxWidth()
                )
                if (currentSensorId == 2) {
                    TotalSamplingProgressBar(viewModel)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PipeCard(
                    name = "铁盐",
                    hasWater = pipe3HasWater,
                    latestMsg = log3?.message ?: "暂无消息",
                    modifier = Modifier.fillMaxWidth()
                )
                if (currentSensorId == 3) {
                    TotalSamplingProgressBar(viewModel)
                }
            }
        }

        // 2. 中部分：10步采样流程折线步骤条与内置实时进度条
        StepProgressBar(
            currentIndex = currentStatusIndex,
            viewModel = viewModel
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 3. 下部分：污水图像快照监视区
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp),
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "物理照片回传 (回传时间: ${displayFile!!.name.substringAfter("IMG_").substringBefore(".jpg")})",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.takePhoto(context) },
                            enabled = !isTakingPhoto,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        ) {
                            if (isTakingPhoto) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = Color.White,
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("拍摄中", fontSize = 10.sp, color = Color.White)
                            } else {
                                Text("📷 单次拍照", fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                } else {
                    PhotoPlaceholder(
                        onTakePhoto = { viewModel.takePhoto(context) },
                        isTakingPhoto = isTakingPhoto
                    )
                }
            }
        }
    }

    if (showImagePreview && displayFile != null) {
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
fun PhotoPlaceholder(onTakePhoto: () -> Unit, isTakingPhoto: Boolean) {
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
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onTakePhoto,
            enabled = !isTakingPhoto,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            modifier = Modifier.height(36.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        ) {
            if (isTakingPhoto) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在拍摄...", fontSize = 11.sp, color = Color.White)
            } else {
                Text("📷 远程拍照", fontSize = 11.sp, color = Color.White)
            }
        }
    }
}

@Composable
fun PipeCard(
    name: String,
    hasWater: Boolean,
    latestMsg: String,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (hasWater) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val strokeColor = if (hasWater) MaterialTheme.colorScheme.primary else Color.Transparent
    val pipeColor = if (hasWater) Color(0xFF2196F3) else Color.Gray

    Card(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = if (hasWater) BorderStroke(1.5.dp, strokeColor) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 水流/管道示意圆形图
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(pipeColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasWater) "💧" else "⚪",
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
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

@Composable
fun StepProgressBar(
    currentIndex: Int,
    viewModel: MonitorViewModel
) {
    val steps = listOf("空闲", "待稳", "取头样", "延时", "取中样", "延时", "取尾样", "延时", "排空", "结束")
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
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val currentSensorId by viewModel.currentSensorId.collectAsState()
            if (currentSensorId != -1) {
                val pumpMax by viewModel.pumpWorkTimeMax.collectAsState()
                if (pumpMax > 0) {
                    PumpProgressBar(viewModel)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

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

                val points = steps.mapIndexed { index, _ ->
                    val x = stepWidth * index + stepWidth / 2
                    val y = yOffsets[index] + nodeRadius
                    Pair(x, y)
                }

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

                    drawPath(
                        path = path,
                        color = inactiveColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )

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

                steps.forEachIndexed { index, stepName ->
                    val isCompleted = index < currentIndex
                    val isActive = index == currentIndex

                    val nodeColor = when {
                        isActive -> activeColor
                        isCompleted -> MaterialTheme.colorScheme.secondary
                        else -> Color.Gray.copy(alpha = 0.5f)
                    }

                    val textColor = when {
                        isActive -> {
                            if (index == 2 || index == 4 || index == 6 || index == 8) {
                                Color(0xFFFFB300)
                            } else {
                                activeColor
                            }
                        }
                        isCompleted -> MaterialTheme.colorScheme.onSurface
                        else -> Color.Gray
                    }

                    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

                    val xPos = stepWidth * index + stepWidth / 2 - nodeRadius
                    val yPos = yOffsets[index]

                    Box(
                        modifier = Modifier
                            .offset(x = xPos, y = yPos)
                            .size(28.dp)
                            .clip(RoundedCornerShape(50))
                            .background(nodeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    val textWidth = 50.dp
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

            if (currentSensorId != -1) {
                val restMax by viewModel.restTimeMax.collectAsState()
                if (restMax > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    RestProgressBar(viewModel, currentIndex)
                }
            }
        }
    }
}

@Composable
fun TotalSamplingProgressBar(viewModel: MonitorViewModel) {
    val totalMax by viewModel.totalSamplingTimeMax.collectAsState()
    val totalRemaining by viewModel.totalSamplingTimeRemaining.collectAsState()
    if (totalMax > 0) {
        val progress = if (totalMax > 0) totalRemaining.toFloat() / totalMax else 0f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.LightGray.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${(totalRemaining + 59) / 60}分 / ${totalMax / 60}分",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun PumpProgressBar(viewModel: MonitorViewModel) {
    val pumpMax by viewModel.pumpWorkTimeMax.collectAsState()
    val pumpRemaining by viewModel.pumpWorkTimeRemaining.collectAsState()
    if (pumpMax > 0) {
        val progress = if (pumpMax > 0) pumpRemaining.toFloat() / pumpMax else 0f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⚡ ${pumpRemaining}秒 / 共 ${pumpMax}秒",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFB300)
            )
            Spacer(modifier = Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFFFB300),
                trackColor = Color.LightGray.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
fun RestProgressBar(viewModel: MonitorViewModel, currentStatusIndex: Int) {
    val restMax by viewModel.restTimeMax.collectAsState()
    val restRemaining by viewModel.restTimeRemaining.collectAsState()
    if (restMax > 0) {
        val progress = if (restMax > 0) restRemaining.toFloat() / restMax else 0f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⏳ ${(restRemaining + 59) / 60}分 / 共 ${restMax / 60}分",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.LightGray.copy(alpha = 0.3f)
            )
        }
    }
}
