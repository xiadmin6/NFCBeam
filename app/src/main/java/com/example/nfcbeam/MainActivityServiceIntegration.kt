package com.example.nfcbeam

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * MainActivity 后台服务集成示例
 * 
 * 将以下代码添加到您的 MainActivity 中以启用后台服务功能
 */

// ========== 步骤 1: 在 MainActivity.onCreate() 开始处添加 ==========
fun ComponentActivity.startBackgroundService() {
    // 👇 关键：App 一打开就启动前台服务
    Intent(this, FileTransferService::class.java).also { intent ->
        intent.action = FileTransferService.ACTION_PREPARE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    Log.d("MainActivity", "✅ 后台服务已启动")
}

// ========== 步骤 2: 检查并请求后台权限 ==========
fun ComponentActivity.checkAndRequestBackgroundPermissions() {
    if (BackgroundPermissionHelper.needsBackgroundPermissions(this)) {
        BackgroundPermissionHelper.showBackgroundPermissionDialog(this) {
            Log.d("MainActivity", "✅ 后台权限检查完成")
        }
    }
}

// ========== 步骤 3: 修改文件传输启动逻辑 ==========
fun ComponentActivity.startFileTransferViaService(fileUris: ArrayList<android.net.Uri>, isSender: Boolean) {
    Intent(this, FileTransferService::class.java).also { intent ->
        intent.action = FileTransferService.ACTION_START_TRANSFER
        intent.putParcelableArrayListExtra(FileTransferService.EXTRA_FILE_URIS, fileUris)
        intent.putExtra(FileTransferService.EXTRA_IS_SENDER, isSender)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    Log.d("MainActivity", "📤 已发送传输命令到服务")
}

// ========== 步骤 4: 停止服务（可选，通常传输完成后自动停止） ==========
fun ComponentActivity.stopBackgroundService() {
    Intent(this, FileTransferService::class.java).also { intent ->
        intent.action = FileTransferService.ACTION_STOP_TRANSFER
        startService(intent)
    }
    Log.d("MainActivity", "🛑 已发送停止命令到服务")
}

/**
 * ========== 完整的 MainActivity.onCreate() 示例 ==========
 * 
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     
 *     // 1️⃣ 第一时间启动后台服务
 *     startBackgroundService()
 *     
 *     // 2️⃣ 检查后台权限
 *     checkAndRequestBackgroundPermissions()
 *     
 *     // 原有的初始化代码...
 *     bluetoothManager = BluetoothManager(this)
 *     fileTransferManager = FileTransferManager(this, bluetoothManager)
 *     // ...
 *     
 *     setContent { /* ... */ }
 * }
 * 
 * ========== 修改传输启动方法 ==========
 * 
 * private fun startTransfer() {
 *     if (selectedFiles.isEmpty()) return
 *     
 *     currentScreen = Screen.TRANSFER_IN_PROGRESS
 *     
 *     // 使用服务启动传输（而非直接调用 fileTransferManager）
 *     startFileTransferViaService(ArrayList(selectedFiles), isSenderMode)
 * }
 * 
 * ========== 可选：绑定服务以获取实时状态 ==========
 * 
 * private var transferService: FileTransferService? = null
 * private var isServiceBound = false
 * 
 * private val serviceConnection = object : ServiceConnection {
 *     override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
 *         val binder = service as? FileTransferService.LocalBinder
 *         transferService = binder?.getService()
 *         isServiceBound = true
 *         
 *         // 注册回调以接收进度更新
 *         transferService?.registerCallback(object : FileTransferService.TransferCallback {
 *             override fun onProgress(progress: Int) {
 *                 runOnUiThread {
 *                     transferProgress = progress.toFloat()
 *                 }
 *             }
 *             
 *             override fun onComplete(success: Boolean, filePath: String?) {
 *                 runOnUiThread {
 *                     isTransferSuccess = success
 *                     currentScreen = Screen.TRANSFER_COMPLETE
 *                 }
 *             }
 *         })
 *     }
 *     
 *     override fun onServiceDisconnected(name: ComponentName?) {
 *         transferService = null
 *         isServiceBound = false
 *     }
 * }
 * 
 * override fun onStart() {
 *     super.onStart()
 *     // 绑定服务以获取状态更新
 *     Intent(this, FileTransferService::class.java).also { intent ->
 *         bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
 *     }
 * }
 * 
 * override fun onStop() {
 *     super.onStop()
 *     // 解绑服务（服务继续在后台运行）
 *     if (isServiceBound) {
 *         transferService?.unregisterCallback(...)
 *         unbindService(serviceConnection)
 *         isServiceBound = false
 *     }
 * }
 */