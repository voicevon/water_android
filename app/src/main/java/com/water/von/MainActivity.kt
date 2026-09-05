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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.water.von.service.MqttService
import com.water.von.ui.main.MainScreen
import com.water.von.theme.Theme
import kotlinx.coroutines.delay

/**
 * 应用主 ActivityMainActivity
 * 负责运行时通知权限申请、启动 MQTT 前台常驻服务以及装载 Compose 布局
 */
class MainActivity : ComponentActivity() {

    companion object {
        var showSensorDebugOnLaunch by mutableStateOf(false)
    }

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
        handleIntent(intent)
        enableEdgeToEdge()

        // 1. 检查并申请通知权限 (Android 13+)
        checkAndRequestNotificationPermission()

        // 1b. 检查并引导开启电池优化白名单（突破国产 ROM Doze 网络限制的关键授权）
        checkAndRequestBatteryOptimizationExemption()

        // 2. 自动启动后台常驻连接前台服务
        startMqttForegroundService()

        // 3. 设置 Compose 界面
        setContent {
            Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showSplash by remember { mutableStateOf(true) }

                    LaunchedEffect(Unit) {
                        delay(3000)
                        showSplash = false
                    }

                    if (showSplash) {
                        SplashScreen()
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_sensor_debug", false) == true) {
            showSensorDebugOnLaunch = true
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
    /**
     * 检查并引导用户开启电池优化白名单（Ignore Battery Optimizations）。
     * 授权后：Android 系统 Doze 模式不再限制该 App 的后台网络访问和 CPU 唤醒，
     * 是实现手机休眠期间实时接收 MQTT 告警的关键前提。
     */
    private fun checkAndRequestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要关闭电池优化")
                .setMessage(
                    "为确保手机休眠期间实时收到污水告警，\n" +
                    "请在下一页面中将本应用设置为【不限制后台活动】。\n\n" +
                    "（如拒绝，手机休眠超过 5 分钟后可能漏报告警）"
                )
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("暂时跳过", null)
                .show()
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = ImageVector.vectorResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(100.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "污水厂药液自动采样系统",
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "2026年2月",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
