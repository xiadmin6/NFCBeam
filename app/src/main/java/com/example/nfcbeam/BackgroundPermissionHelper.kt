package com.example.nfcbeam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 后台运行权限辅助类
 * 帮助请求电池优化豁免和通知权限
 */
object BackgroundPermissionHelper {
    
    private const val TAG = "BackgroundPermission"
    
    /**
     * 检查是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = context.packageName
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true // Android 6.0 以下不需要
    }
    
    /**
     * 请求忽略电池优化
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(activity)) {
                try {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    Log.d(TAG, "已请求忽略电池优化")
                } catch (e: Exception) {
                    Log.e(TAG, "无法打开电池优化设置", e)
                    // 备用方案：打开电池优化设置页面
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        activity.startActivity(intent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "无法打开电池优化设置页面", e2)
                    }
                }
            } else {
                Log.d(TAG, "已经忽略电池优化")
            }
        }
    }
    
    /**
     * 检查通知权限（Android 13+）
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true // Android 13 以下不需要
    }
    
    /**
     * 检查是否需要请求后台运行权限
     */
    fun needsBackgroundPermissions(context: Context): Boolean {
        val needsBatteryOptimization = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !isIgnoringBatteryOptimizations(context)
        
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission(context)
        
        return needsBatteryOptimization || needsNotification
    }
    
    /**
     * 获取后台权限状态描述
     */
    fun getPermissionStatusMessage(context: Context): String {
        val messages = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isIgnoringBatteryOptimizations(context)) {
                messages.add("✅ 已允许后台运行")
            } else {
                messages.add("⚠️ 需要允许后台运行")
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasNotificationPermission(context)) {
                messages.add("✅ 已允许通知")
            } else {
                messages.add("⚠️ 需要通知权限")
            }
        }
        
        return if (messages.isEmpty()) {
            "✅ 后台运行已就绪"
        } else {
            messages.joinToString("\n")
        }
    }
    
    /**
     * 打开应用设置页面
     */
    fun openAppSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivity(intent)
            Log.d(TAG, "已打开应用设置页面")
        } catch (e: Exception) {
            Log.e(TAG, "无法打开应用设置页面", e)
        }
    }
    
    /**
     * 显示后台运行引导对话框
     */
    fun showBackgroundPermissionDialog(activity: Activity, onConfirm: () -> Unit) {
        if (!needsBackgroundPermissions(activity)) {
            onConfirm()
            return
        }
        
        val message = buildString {
            append("为了确保文件传输不被中断，需要以下权限：\n\n")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !isIgnoringBatteryOptimizations(activity)) {
                append("• 允许后台运行（忽略电池优化）\n")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission(activity)) {
                append("• 允许显示通知\n")
            }
            
            append("\n是否现在设置？")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("后台运行权限")
            .setMessage(message)
            .setPositiveButton("去设置") { _, _ ->
                requestIgnoreBatteryOptimizations(activity)
                onConfirm()
            }
            .setNegativeButton("稍后") { _, _ ->
                onConfirm()
            }
            .setCancelable(false)
            .show()
    }
}