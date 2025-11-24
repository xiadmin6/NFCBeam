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
        private val SERVICE_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
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
                            Log.d(TAG, "发现设备: ${it.name} - ${it.address}")
                        } else {
                            Log.d(TAG, "发现设备: 无权限访问设备信息")
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "设备发现完成")
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
    
    fun getBluetoothInfoForNFC(): String {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法获取蓝牙信息")
            return "Unknown|Unknown|${SERVICE_UUID}"
        }
        return try {
            val deviceName = bluetoothAdapter?.name ?: "Unknown"
            val deviceAddress = bluetoothAdapter?.address ?: "Unknown"
            "$deviceName|$deviceAddress|${SERVICE_UUID}"
        } catch (e: SecurityException) {
            Log.e(TAG, "权限被拒绝，无法获取蓝牙信息", e)
            "Unknown|Unknown|${SERVICE_UUID}"
        }
    }
    
    fun processNfcBluetoothInfo(bluetoothInfo: String) {
        try {
            val parts = bluetoothInfo.split("|")
            if (parts.size >= 3) {
                val deviceName = parts[0]
                val deviceAddress = parts[1]
                val serviceUUID = UUID.fromString(parts[2])
                
                Log.d(TAG, "从NFC接收蓝牙信息: $deviceName ($deviceAddress)")
                Toast.makeText(context, "从NFC接收蓝牙信息: $deviceName", Toast.LENGTH_LONG).show()
                
                // 连接到设备
                connectToDevice(deviceAddress, serviceUUID)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理NFC蓝牙信息失败", e)
            Toast.makeText(context, "处理NFC数据失败", Toast.LENGTH_SHORT).show()
        }
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
                    Log.d(TAG, "开始设备发现")
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
            Log.d(TAG, "已经在连接中")
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
                        Log.d(TAG, "尝试连接到设备: ${it.name}")
                        
                        // 取消正在进行的发现
                        try {
                            bluetoothAdapter?.cancelDiscovery()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "权限被拒绝，无法取消设备发现", e)
                        }
                        
                        // 创建socket连接
                        val socket = it.createRfcommSocketToServiceRecord(serviceUUID)
                        socket.connect()
                        
                        clientSocket = socket
                        isConnecting = false
                        
                        Log.d(TAG, "连接成功: ${it.name}")
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
            Log.d(TAG, "服务器已经在运行")
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
                
                Log.d(TAG, "蓝牙服务器已启动，等待连接...")
                
                while (isServerRunning) {
                    try {
                        val socket = serverSocket?.accept()
                        socket?.let {
                            Log.d(TAG, "客户端已连接")
                            clientSocket = it
                            stateListener?.onDeviceConnected(it.remoteDevice)
                            
                            // 可以在这里处理文件接收
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
        isServerRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "关闭服务器socket失败", e)
        }
        serverSocket = null
    }
    
    fun disconnect() {
        try {
            clientSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "关闭客户端socket失败", e)
        }
        clientSocket = null
        stateListener?.onDeviceDisconnected()
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
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            // 接收器可能未注册
        }
        stopServer()
        disconnect()
    }
}
