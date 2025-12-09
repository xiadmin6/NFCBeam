package com.example.nfcbeam

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

class BluetoothManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothManager"
        // ✅ 优化1: 使用 SPP 标准 UUID，避免协商
        private val SERVICE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP UUID
        private const val SERVICE_NAME = "NFCBeamFileTransfer"
    }
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var isServerRunning = false
    private var isConnecting = false
    
    // 蓝牙状态监听器
    interface BluetoothStateListener {
        fun onBluetoothStateChanged(enabled: Boolean)
        fun onDeviceDiscovered(device: BluetoothDevice)
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected()
        fun onConnectionFailed(error: String)
    }

    fun startBluetoothServer() = startServer()
    fun stopBluetoothServer() = stopServer()
    private var stateListener: BluetoothStateListener? = null
    
    // 蓝牙广播接收器
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            stateListener?.onBluetoothStateChanged(true)
                            Toast.makeText(context, "蓝牙已启用", Toast.LENGTH_SHORT).show()
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            stateListener?.onBluetoothStateChanged(false)
                            Toast.makeText(context, "蓝牙已禁用", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        stateListener?.onDeviceDiscovered(it)
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            // ✅ 优化4: 日志分级 - 使用 Log.v() 避免 release 版本性能损耗
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "发现设备: ${it.name} - ${it.address}")
                            }
                        } else {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "发现设备: 无权限访问设备信息")
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "设备发现完成")
                    }
                    Toast.makeText(context, "设备发现完成", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    fun initialize() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "设备不支持蓝牙")
            Toast.makeText(context, "设备不支持蓝牙", Toast.LENGTH_LONG).show()
            return
        }
        
        // 注册蓝牙广播接收器
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, bluetoothReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        // 检查蓝牙状态
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(context, "请启用蓝牙", Toast.LENGTH_LONG).show()
        }
    }
    
    fun enableBluetooth(): Boolean {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法启用蓝牙")
            return false
        }
        return try {
            bluetoothAdapter?.enable() ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "权限被拒绝，无法启用蓝牙", e)
            false
        }
    }
    
    fun disableBluetooth(): Boolean {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法禁用蓝牙")
            return false
        }
        return try {
            bluetoothAdapter?.disable() ?: false
        } catch (e: SecurityException) {
            Log.e(TAG, "权限被拒绝，无法禁用蓝牙", e)
            false
        }
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    
    fun autoConnectToDevice(deviceAddress: String, serviceUUID: UUID = SERVICE_UUID) {
        if (isConnecting) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "已经在连接中")
            }
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法自动连接设备")
            stateListener?.onConnectionFailed("缺少蓝牙权限")
            return
        }
        
        isConnecting = true
        Thread {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                device?.let {
                    try {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "自动连接到设备: ${it.name}")
                        }
                        
                        // 确保蓝牙已启用
                        if (!isBluetoothEnabled()) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "蓝牙未启用，正在启用...")
                            }
                            enableBluetooth()
                            // 等待蓝牙启用
                            Thread.sleep(2000)
                        }
                        
                        // ✅ 优化2: 连接前停止 BLE 扫描，释放资源
                        try {
                            if (bluetoothAdapter?.isDiscovering == true) {
                                bluetoothAdapter?.cancelDiscovery()
                                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                    Log.v(TAG, "已停止蓝牙扫描，释放资源")
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "权限被拒绝，无法取消设备发现", e)
                        }
                        
                        // 创建socket连接
                        val socket = it.createRfcommSocketToServiceRecord(serviceUUID)
                        socket.connect()
                        
                        clientSocket = socket
                        isConnecting = false
                        
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "自动连接成功: ${it.name}")
                        }
                        stateListener?.onDeviceConnected(it)
                        
                        // 可以在这里开始文件传输
                    } catch (e: SecurityException) {
                        Log.e(TAG, "权限被拒绝，无法自动连接设备", e)
                        stateListener?.onConnectionFailed("权限被拒绝")
                        isConnecting = false
                    }
                } ?: run {
                    Log.e(TAG, "未找到设备: $deviceAddress")
                    stateListener?.onConnectionFailed("未找到设备")
                    isConnecting = false
                }
            } catch (e: IOException) {
                Log.e(TAG, "自动连接失败", e)
                stateListener?.onConnectionFailed(e.message ?: "自动连接失败")
                isConnecting = false
            }
        }.start()
    }
    
    fun startDiscovery() {
        if (!hasBluetoothPermissions()) {
            Toast.makeText(context, "需要蓝牙权限", Toast.LENGTH_SHORT).show()
            return
        }
        
        bluetoothAdapter?.let { adapter ->
            try {
                if (adapter.isDiscovering) {
                    adapter.cancelDiscovery()
                }
                
                if (adapter.startDiscovery()) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "开始设备发现")
                    }
                    Toast.makeText(context, "开始搜索设备...", Toast.LENGTH_SHORT).show()
                } else {
                    Log.e(TAG, "无法开始设备发现")
                    Toast.makeText(context, "无法开始搜索设备", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "权限被拒绝，无法开始设备发现", e)
                Toast.makeText(context, "权限被拒绝，无法搜索设备", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    fun stopDiscovery() {
        if (hasBluetoothPermissions()) {
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.e(TAG, "权限被拒绝，无法停止设备发现", e)
            }
        }
    }
    
    fun getPairedDevices(): List<String> {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法获取配对设备")
            return emptyList()
        }
        return try {
            val pairedDevices = bluetoothAdapter?.bondedDevices ?: return emptyList()
            pairedDevices.map { 
                try {
                    "${it.name}\n${it.address}"
                } catch (e: SecurityException) {
                    "Unknown\nUnknown"
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "权限被拒绝，无法获取配对设备", e)
            emptyList()
        }
    }
    
    fun connectToDevice(deviceAddress: String, serviceUUID: UUID = SERVICE_UUID) {
        if (isConnecting) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "已经在连接中")
            }
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法连接设备")
            stateListener?.onConnectionFailed("缺少蓝牙权限")
            return
        }
        
        isConnecting = true
        Thread {
            try {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                device?.let {
                    try {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "尝试连接到设备: ${it.name}")
                        }
                        
                        // ✅ 优化2: 连接前停止蓝牙扫描，释放资源
                        try {
                            if (bluetoothAdapter?.isDiscovering == true) {
                                bluetoothAdapter?.cancelDiscovery()
                                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                    Log.v(TAG, "已停止蓝牙扫描，释放资源")
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "权限被拒绝，无法取消设备发现", e)
                        }
                        
                        // 创建socket连接
                        val socket = it.createRfcommSocketToServiceRecord(serviceUUID)
                        socket.connect()
                        
                        clientSocket = socket
                        isConnecting = false
                        
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "连接成功: ${it.name}")
                        }
                        stateListener?.onDeviceConnected(it)
                        
                        // 可以在这里开始文件传输
                    } catch (e: SecurityException) {
                        Log.e(TAG, "权限被拒绝，无法连接设备", e)
                        stateListener?.onConnectionFailed("权限被拒绝")
                        isConnecting = false
                    }
                } ?: run {
                    Log.e(TAG, "未找到设备: $deviceAddress")
                    stateListener?.onConnectionFailed("未找到设备")
                    isConnecting = false
                }
            } catch (e: IOException) {
                Log.e(TAG, "连接失败", e)
                stateListener?.onConnectionFailed(e.message ?: "连接失败")
                isConnecting = false
            }
        }.start()
    }
    
    fun startServer() {
        if (isServerRunning) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "服务器已经在运行")
            }
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法启动服务器")
            stateListener?.onConnectionFailed("缺少蓝牙权限")
            return
        }
        
        Thread {
            try {
                serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                isServerRunning = true
                
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "蓝牙服务器已启动，等待连接...")
                }
                
                while (isServerRunning) {
                    try {
                        val socket = serverSocket?.accept()
                        socket?.let {
                            try {
                                val deviceName = if (hasBluetoothPermissions()) {
                                    it.remoteDevice.name ?: "Unknown"
                                } else {
                                    "Unknown"
                                }
                                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                    Log.v(TAG, "客户端已连接: $deviceName")
                                }
                                clientSocket = it
                                stateListener?.onDeviceConnected(it.remoteDevice)
                                
                                // 服务器接受连接后继续监听新连接
                            } catch (e: SecurityException) {
                                Log.e(TAG, "权限被拒绝，无法获取设备信息", e)
                                clientSocket = it
                                stateListener?.onDeviceConnected(it.remoteDevice)
                            }
                        }
                    } catch (e: IOException) {
                        if (isServerRunning) {
                            Log.e(TAG, "接受连接失败", e)
                        }
                        break
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "权限被拒绝，无法启动服务器", e)
                stateListener?.onConnectionFailed("权限被拒绝")
            } catch (e: IOException) {
                Log.e(TAG, "启动服务器失败", e)
                stateListener?.onConnectionFailed(e.message ?: "启动服务器失败")
            }
        }.start()
    }
    
    fun stopServer() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "停止蓝牙服务器...")
        }
        
        // ✅ 优化5: 正确停止服务器，避免资源泄漏
        isServerRunning = false
        
        try {
            serverSocket?.let { socket ->
                try {
                    socket.close()
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "服务器 socket 已关闭")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "关闭服务器socket失败", e)
                }
            }
        } finally {
            serverSocket = null
        }
    }
    
    fun disconnect() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "开始断开连接...")
        }
        
        // ✅ 优化5: 正确关闭 socket，避免资源泄漏
        try {
            // 1. 先关闭 socket（这会中断所有阻塞的 I/O 操作）
            clientSocket?.let { socket ->
                try {
                    // 关闭输入流
                    socket.inputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "关闭输入流失败", e)
                }
                
                try {
                    // 关闭输出流
                    socket.outputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "关闭输出流失败", e)
                }
                
                try {
                    // 最后关闭 socket 本身
                    if (socket.isConnected) {
                        socket.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "关闭socket失败", e)
                }
                
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Socket 已完全关闭")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "断开连接过程中发生异常", e)
        } finally {
            // 2. 清空引用，确保 GC 可以回收
            clientSocket = null
            
            // 3. 通知监听器
            stateListener?.onDeviceDisconnected()
            
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "断开连接完成")
            }
        }
    }
    
    fun getClientSocket(): BluetoothSocket? {
        return clientSocket
    }
    
    fun setStateListener(listener: BluetoothStateListener) {
        this.stateListener = listener
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun cleanup() {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "开始清理 BluetoothManager 资源...")
        }
        
        try {
            // 1. 注销广播接收器
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            // 接收器可能未注册
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "广播接收器未注册或已注销")
            }
        }
        
        // 2. 停止服务器
        stopServer()
        
        // 3. 断开客户端连接
        disconnect()
        
        // 4. 清空状态监听器，避免内存泄漏
        stateListener = null
        
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "BluetoothManager 资源清理完成")
        }
    }
}
