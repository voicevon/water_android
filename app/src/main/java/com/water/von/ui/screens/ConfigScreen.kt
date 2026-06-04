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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.water.von.ui.viewmodel.ConfigViewModel

/**
 * MQTT 通信连接调试配置页 ConfigScreen
 * 提供 MQTT 连接参数表单，并在底部显示滚动黑色调试控制台
 */
@Composable
fun ConfigScreen(viewModel: ConfigViewModel = viewModel()) {
    val brokerIp by viewModel.brokerIp.collectAsState()
    val brokerPort by viewModel.brokerPort.collectAsState()
    val username by viewModel.username.collectAsState()
    val password by viewModel.password.collectAsState()
    val clientId by viewModel.clientId.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val consoleLogs by viewModel.consoleLogs.collectAsState()

    var isPasswordVisible by remember { mutableStateOf(false) }
    val mainScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(mainScrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "云端服务器参数",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // 1. MQTT 参数表单
        OutlinedTextField(
            value = brokerIp,
            onValueChange = { viewModel.updateBrokerIp(it) },
            label = { Text("服务器域名或IP地址") },
            placeholder = { Text("例如 voicevon.vicp.io") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = brokerPort.toString(),
            onValueChange = { 
                it.toIntOrNull()?.let { port -> viewModel.updateBrokerPort(port) }
            },
            label = { Text("端口号") },
            placeholder = { Text("1883") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = clientId,
            onValueChange = { viewModel.updateClientId(it) },
            label = { Text("客户端 ID (ClientID)") },
            placeholder = { Text("为空时自动生成") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = { viewModel.updateUsername(it) },
            label = { Text("认证用户名") },
            placeholder = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = password,
            onValueChange = { viewModel.updatePassword(it) },
            label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                    Text(if (isPasswordVisible) "隐藏" else "显示")
                }
            },
            shape = RoundedCornerShape(8.dp)
        )

        // 2. 连接/保存控制大按钮
        Button(
            onClick = { viewModel.saveAndConnect() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isConnected) "应用并重启连接" else "保存并建立连接",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        // 3. 网络日志控制台看板
        Text(
            text = "网络底层诊断日志",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E1E1E)) // 纯黑底色，命令行质感
                .padding(8.dp)
        ) {
            val consoleScrollState = rememberScrollState()
            
            // 实时打印滚动诊断日志
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(consoleScrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            color = if (log.contains("连接成功")) Color(0xFF4CAF50) else if (log.contains("失败") || log.contains("丢失")) Color(0xFFF44336) else Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
