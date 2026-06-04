package com.water.von.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.water.von.data.LogEntry
import com.water.von.ui.viewmodel.LogsViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 历史日志查询页 LogsScreen
 * 支持日期选择过滤、关键字模糊查询，列表展示通道动作，支持图片弹窗大图预览
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = viewModel()) {
    val context = LocalContext.current
    val selectedDate by viewModel.selectedDate.collectAsState()
    val searchKeyword by viewModel.searchKeyword.collectAsState()
    val logsList by viewModel.logsList.collectAsState()

    // 选中查看大图的日志实体
    var activePreviewEntry by remember { mutableStateOf<LogEntry?>(null) }

    // 初始化日历对象
    val calendar = Calendar.getInstance()
    if (selectedDate.isNotEmpty()) {
        try {
            val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).parse(selectedDate)
            if (date != null) calendar.time = date
        } catch (_: Exception) {}
    }

    val datePickerDialog = remember(context, selectedDate) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                val formattedDate = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)
                viewModel.setDateAndLoad(formattedDate)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 顶部检索工具栏 (日期选择按钮 + 模糊搜索框)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { datePickerDialog.show() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
            ) {
                // 将 yyyyMMdd 转化为友好显示：yyyy-MM-dd
                val displayDate = try {
                    val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).parse(selectedDate)
                    if (date != null) SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date) else selectedDate
                } catch (_: Exception) {
                    selectedDate
                }
                Text(text = displayDate)
            }

            OutlinedTextField(
                value = searchKeyword,
                onValueChange = { viewModel.setKeyword(it) },
                label = { Text("搜索动作内容") },
                placeholder = { Text("输入关键字...") },
                modifier = Modifier.weight(1.5f),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
        }

        // 2. 日志列表项
        if (logsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📂 该日暂无动作日志记录",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logsList) { entry ->
                    LogItemCard(entry, viewModel) {
                        activePreviewEntry = entry
                    }
                }
            }
        }
    }

    // 3. 图片放大弹窗对话框
    activePreviewEntry?.let { entry ->
        val photoFile = viewModel.getPhotoFile(entry.imagePath)
        if (photoFile != null && photoFile.exists()) {
            AlertDialog(
                onDismissRequest = { activePreviewEntry = null },
                confirmButton = {
                    TextButton(onClick = { activePreviewEntry = null }) {
                        Text("关闭")
                    }
                },
                title = { Text("现场污水抓拍 (通道 ${entry.channel})") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(photoFile)
                                .crossfade(true)
                                .build(),
                            contentDescription = "大图预览",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "时间: ${entry.time}\n动作: ${entry.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun LogItemCard(entry: LogEntry, viewModel: LogsViewModel, onImageClick: () -> Unit) {
    val levelColor = when (entry.level) {
        "ERROR" -> Color(0xFFFFCDD2)  // 淡红色
        "WARN" -> Color(0xFFFFE0B2)   // 淡橘色
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = levelColor),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badgeColor = when (entry.channel) {
                        1 -> Color(0xFF2196F3)
                        2 -> Color(0xFF4CAF50)
                        else -> Color(0xFFFF9800)
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "C${entry.channel}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // 图像缩略图呈现
            if (entry.imagePath.isNotEmpty()) {
                val photoFile = viewModel.getPhotoFile(entry.imagePath)
                if (photoFile != null && photoFile.exists()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photoFile)
                            .crossfade(true)
                            .build(),
                        contentDescription = "污水缩略图",
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onImageClick() },
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}
