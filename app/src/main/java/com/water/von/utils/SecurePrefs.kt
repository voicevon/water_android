package com.water.von.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密首选项工具类
 * 负责安全读取与保存 MQTT 连接密码及敏感参数
 * 内置异常降级机制防止 Keystore 异常崩溃
 */
object SecurePrefs {
    fun get(context: Context, name: String = "mqtt_config"): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
    }
}
