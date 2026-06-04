package com.water.von

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.water.von.service.MqttService
import com.water.von.ui.main.MainScreen
import com.water.von.theme.Theme

/**
 * 应用主 ActivityMainActivity
 * 负责运行时通知权限申请、启动 MQTT 前台常驻服务以及装载 Compose 布局
 */
class MainActivity : ComponentActivity() {

    // 声明运行时权限申请回调 (Android 13+ 通知权限)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "通知权限已启用，服务运行状态正常", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "警告：未开启通知权限，异常日志报警将无法即时提示！", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. 检查并申请通知权限 (Android 13+)
        checkAndRequestNotificationPermission()

        // 2. 自动启动后台常驻连接前台服务
        startMqttForegroundService()

        // 3. 设置 Compose 界面
        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    /**
     * 检查并请求运行时通知权限
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33+)
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(permission)
            }
        }
    }

    /**
     * 启动前台 MQTT 网络连接服务
     */
    private fun startMqttForegroundService() {
        val serviceIntent = Intent(this, MqttService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Android 8.0+
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
