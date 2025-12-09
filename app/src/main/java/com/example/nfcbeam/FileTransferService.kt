package com.example.nfcbeam

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * 文件传输前台服务
 * 负责在后台管理蓝牙连接和文件传输，即使 Activity 被销毁也能继续运行
 */
class FileTransferService : Service(), FileTransferManager.TransferListener {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "file_transfer_channel"
        private const val CHANNEL_NAME = "文件传输服务"
        
        const val ACTION_PREPARE = "com.example.nfcbeam.PREPARE"
        const val ACTION_START_TRANSFER = "com.example.nfcbeam.START_TRANSFER"
        const val ACTION_STOP_TRANSFER = "com.example.nfcbeam.STOP_TRANSFER"
        const val EXTRA_FILE_URIS = "file_uris"
        const val EXTRA_IS_SENDER = "is_sender"
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var bluetoothManager: BluetoothManager? = null
    private var fileTransferManager: FileTransferManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 传输状态回调
    private val transferCallbacks = mutableListOf<TransferCallback>()
    
    // 当前传输状态
    private var isTransferring = false
    private var currentProgress = 0
    private var currentFileName = ""
    private var totalFiles = 0
    private var transferredFiles = 0

    inner class LocalBinder : Binder() {
        fun getService(): FileTransferService = this@FileTransferService
    }

    override fun onCreate() {
        super.onCreate()
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 初始化管理器
        bluetoothManager = BluetoothManager(this)
        fileTransferManager = FileTransferManager(this, bluetoothManager!!)
        fileTransferManager?.setTransferListener(this)
        
        // 获取 WakeLock 防止 CPU 休眠
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NFCBeam::FileTransferWakeLock"
        )
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("服务已启动", 0))
        
        Log.d("FileTransferService", "✅ 服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                // 服务已启动，处于待命状态
                Log.d("FileTransferService", "📡 服务已准备就绪，等待传输命令")
                updateNotification("NFCBeam 已在后台运行，等待文件传输...", 0)
            }
            ACTION_START_TRANSFER -> {
                val fileUris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra<Uri>(EXTRA_FILE_URIS, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(EXTRA_FILE_URIS) as? ArrayList<Uri>
                }
                val isSender = intent.getBooleanExtra(EXTRA_IS_SENDER, false)
                
                if (fileUris != null && fileUris.isNotEmpty()) {
                    startFileTransfer(fileUris, isSender)
                } else {
                    Log.w("FileTransferService", "⚠️ 未提供文件URI")
                }
            }
            ACTION_STOP_TRANSFER -> {
                stopFileTransfer()
            }
            null -> {
                // 默认启动：显示待命通知
                Log.d("FileTransferService", "📡 服务启动（无特定操作）")
                updateNotification("NFCBeam 已准备就绪", 0)
            }
        }
        
        // START_STICKY: 服务被杀死后会自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 释放资源
        isTransferring = false
        serviceScope.cancel()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        bluetoothManager?.cleanup()
        
        Log.d("FileTransferService", "🛑 服务已销毁")
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示文件传输进度"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(contentText: String, progress: Int): Notification {
        // 点击通知返回 MainActivity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NFCBeam 文件传输")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.nfcbeam)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 显示进度条
        if (progress > 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    /**
     * 更新通知
     */
    private fun updateNotification(contentText: String, progress: Int) {
        val notification = createNotification(contentText, progress)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 开始文件传输
     */
    fun startFileTransfer(fileUris: List<Uri>, isSender: Boolean) {
        if (isTransferring) {
            Log.w("FileTransferService", "传输已在进行中")
            return
        }

        isTransferring = true
        totalFiles = fileUris.size
        
        // 获取 WakeLock
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(30 * 60 * 1000L) // 最多持有 30 分钟
        }

        Log.d("FileTransferService", "🚀 开始文件传输: ${fileUris.size} 个文件")
        updateNotification("准备传输 ${fileUris.size} 个文件...", 0)
        
        if (isSender) {
            // 发送文件
            fileTransferManager?.startFileTransfer(fileUris)
        } else {
            // 接收文件
            fileTransferManager?.startFileReceiver()
        }
    }

    /**
     * 停止文件传输
     */
    fun stopFileTransfer() {
        isTransferring = false
        fileTransferManager?.cancelTransfer()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.d("FileTransferService", "🛑 服务已停止")
    }

    // ============ FileTransferManager.TransferListener 实现 ============
    
    override fun onTransferStarted(totalFiles: Int, totalSize: Long) {
        Log.d("FileTransferService", "📤 传输开始: $totalFiles 个文件, 总大小: $totalSize 字节")
        this.totalFiles = totalFiles
        updateNotification("开始传输 $totalFiles 个文件", 0)
        notifyProgress(0)
    }

    override fun onFileTransferStarted(fileInfo: FileTransferManager.FileInfo, currentFile: Int, totalFiles: Int) {
        Log.d("FileTransferService", "📄 文件传输开始: ${fileInfo.fileName} ($currentFile/$totalFiles)")
        currentFileName = fileInfo.fileName
        this.totalFiles = totalFiles
        transferredFiles = currentFile - 1
        updateNotification("传输中: ${fileInfo.fileName} ($currentFile/$totalFiles)", currentProgress)
    }

    override fun onTransferProgress(transferredBytes: Long, totalBytes: Long, currentFile: Int, totalFiles: Int) {
        val progress = if (totalBytes > 0) {
            ((transferredBytes.toFloat() / totalBytes.toFloat()) * 100).toInt()
        } else {
            0
        }
        
        currentProgress = progress
        this.totalFiles = totalFiles
        transferredFiles = currentFile - 1
        
        updateNotification("传输中: $currentFileName ($currentFile/$totalFiles)", progress)
        notifyProgress(progress)
    }

    override fun onTransferCompleted(transferredFiles: List<FileTransferManager.FileInfo>, totalSize: Long) {
        Log.d("FileTransferService", "✅ 传输完成: ${transferredFiles.size} 个文件")
        isTransferring = false
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        updateNotification("传输完成: ${transferredFiles.size} 个文件", 100)
        notifyComplete(true, null)
        
        // ✅ 修复：传输完成后断开蓝牙连接，确保下次可以重新连接
        bluetoothManager?.disconnect()
        Log.d("FileTransferService", "✅ 传输完成，已断开蓝牙连接")
        
        // 3秒后停止服务
        serviceScope.launch {
            delay(3000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTransferError(error: String) {
        Log.e("FileTransferService", "❌ 传输错误: $error")
        isTransferring = false
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        updateNotification("传输失败: $error", 0)
        notifyComplete(false, null)
        
        // ✅ 修复：传输错误后断开蓝牙连接，确保下次可以重新连接
        bluetoothManager?.disconnect()
        Log.d("FileTransferService", "✅ 传输错误，已断开蓝牙连接")
        
        // 5秒后停止服务
        serviceScope.launch {
            delay(5000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onTransferCancelled() {
        Log.d("FileTransferService", "⚠️ 传输已取消")
        isTransferring = false
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        updateNotification("传输已取消", 0)
        
        // ✅ 修复：通知所有回调传输已取消
        notifyComplete(false, null)
        
        // ✅ 断开蓝牙连接，确保下次可以重新连接
        bluetoothManager?.disconnect()
        Log.d("FileTransferService", "✅ 已断开蓝牙连接")
        
        serviceScope.launch {
            delay(2000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * 注册传输回调
     */
    fun registerCallback(callback: TransferCallback) {
        if (!transferCallbacks.contains(callback)) {
            transferCallbacks.add(callback)
        }
    }

    /**
     * 注销传输回调
     */
    fun unregisterCallback(callback: TransferCallback) {
        transferCallbacks.remove(callback)
    }

    /**
     * 通知进度更新
     */
    private fun notifyProgress(progress: Int) {
        transferCallbacks.forEach { it.onProgress(progress) }
    }

    /**
     * 通知传输完成
     */
    private fun notifyComplete(success: Boolean, filePath: String?) {
        transferCallbacks.forEach { it.onComplete(success, filePath) }
    }

    /**
     * 获取蓝牙管理器
     */
    fun getBluetoothManager(): BluetoothManager? = bluetoothManager

    /**
     * 获取文件传输管理器
     */
    fun getFileTransferManager(): FileTransferManager? = fileTransferManager

    /**
     * 获取当前传输状态
     */
    fun isTransferring(): Boolean = isTransferring

    /**
     * 获取当前进度
     */
    fun getCurrentProgress(): Int = currentProgress

    /**
     * 传输回调接口
     */
    interface TransferCallback {
        fun onProgress(progress: Int)
        fun onComplete(success: Boolean, filePath: String?)
    }
}