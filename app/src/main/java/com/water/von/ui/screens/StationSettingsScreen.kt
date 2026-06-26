package com.water.von.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.von.service.MqttService

/**
 * 远程站点配置页面 StationSettingsScreen
 * 提供站点英文名（MQTT 设备标识）与站点中文名（界面全局标题）的编辑与持久化保存
 */
@Composable
fun StationSettingsScreen() {
    val context = LocalContext.current
    var englishName by remember { mutableStateOf("") }
    var chineseName by remember { mutableStateOf("") }

    // 检查 MQTT 调试是否正在运行，如果正在运行则锁定配置修改
    val isMqttDebuggingActive = MqttService.activeSensorPrefix != null

    // 首次进入加载已保存的配置
    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
        englishName = sp.getString("prefix_name", "dongzhan") ?: "dongzhan"
        chineseName = sp.getString("station_chinese_name", "济南东站污水厂") ?: "济南东站污水厂"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "远程站点配置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (isMqttDebuggingActive) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(
                    text = "⚠️ 传感器调试处于运行状态，锁定配置项。请先在“传感器调试”中停止调试后再修改站点参数。",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    text = "📝 配置编辑模式。站点英文名对应节点筛选标识，站点中文名对应界面顶部标题。",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        OutlinedTextField(
            value = englishName,
            onValueChange = { if (!isMqttDebuggingActive) englishName = it },
            label = { Text("站点英文名 (设备标识)") },
            placeholder = { Text("例如: dongzhan") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isMqttDebuggingActive,
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = chineseName,
            onValueChange = { if (!isMqttDebuggingActive) chineseName = it },
            label = { Text("站点中文名 (系统标题)") },
            placeholder = { Text("例如: 济南东站污水厂") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isMqttDebuggingActive,
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val sp = context.getSharedPreferences("mqtt_debug_config", Context.MODE_PRIVATE)
                sp.edit().apply {
                    putString("prefix_name", englishName)
                    putString("station_chinese_name", chineseName)
                    apply()
                }
                
                // 同步更新全局的中英文参数
                MqttService.updateStationChineseName(context, chineseName)
                
                Toast.makeText(context, "站点配置已保存，立即生效", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !isMqttDebuggingActive,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("保存配置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
