package com.example.nfcbeam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * 服务连接管理器
 * 负责管理 Activity 与 FileTransferService 之间的连接
 */
class ServiceConnectionManager(private val context: Context) {

    private var service: FileTransferService? = null
    private var isBound = false
    private var onServiceConnected: ((FileTransferService) -> Unit)? = null
    private var onServiceDisconnected: (() -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d("ServiceConnection", "Service connected")
            val localBinder = binder as? FileTransferService.LocalBinder
            service = localBinder?.getService()
            isBound = true
            
            service?.let { onServiceConnected?.invoke(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("ServiceConnection", "Service disconnected")
            service = null
            isBound = false
            onServiceDisconnected?.invoke()
        }
    }

    /**
     * 绑定服务
     */
    fun bindService(
        onConnected: (FileTransferService) -> Unit,
        onDisconnected: (() -> Unit)? = null
    ) {
        this.onServiceConnected = onConnected
        this.onServiceDisconnected = onDisconnected

        val intent = Intent(context, FileTransferService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 解绑服务
     */
    fun unbindService() {
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
            service = null
        }
    }

    /**
     * 启动服务
     */
    fun startService() {
        val intent = Intent(context, FileTransferService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * 停止服务
     */
    fun stopService() {
        val intent = Intent(context, FileTransferService::class.java)
        context.stopService(intent)
    }

    /**
     * 启动文件传输
     */
    fun startFileTransfer(fileUris: ArrayList<android.net.Uri>, isSender: Boolean) {
        val intent = Intent(context, FileTransferService::class.java).apply {
            action = FileTransferService.ACTION_START_TRANSFER
            putParcelableArrayListExtra(FileTransferService.EXTRA_FILE_URIS, fileUris)
            putExtra(FileTransferService.EXTRA_IS_SENDER, isSender)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * 停止文件传输
     */
    fun stopFileTransfer() {
        val intent = Intent(context, FileTransferService::class.java).apply {
            action = FileTransferService.ACTION_STOP_TRANSFER
        }
        context.startService(intent)
    }

    /**
     * 获取服务实例
     */
    fun getService(): FileTransferService? = service

    /**
     * 检查是否已绑定
     */
    fun isBound(): Boolean = isBound
}