package com.example.nfcbeam

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.security.SecureRandom
import java.util.UUID
import androidx.annotation.RequiresPermission

/**
 * 蓝牙Out-of-Band配对管理器
 * 使用BLE广播和扫描实现OOB配对，替代NFC传输蓝牙信息
 */
class BluetoothOOBPairingManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothOOBPairing"
        // 使用16位UUID以节省空间 (0xABCD)
        private val OOB_SERVICE_UUID = ParcelUuid(UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB"))
        private const val ADVERTISE_TIMEOUT_MS = 30000L // 30秒广告超时
        private const val SCAN_TIMEOUT_MS = 30000L // 30秒扫描超时
        private const val MAX_PAIRING_CODE_LENGTH = 4 // 限制配对码长度为4位数字
        private const val PAIRING_RETRY_DELAY_MS = 5000L // 5秒等待时间
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }
    
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var isAdvertising = false
    private var isScanning = false
    private var pairingCode: String? = null
    private var discoveredDevice: BluetoothDevice? = null
    private var pairingAttemptCount = 0
    private val MAX_PAIRING_ATTEMPTS = 3
    private var isWaitingForRetry = false
    private val handler = Handler(Looper.getMainLooper())
    
    // OOB配对监听器
    interface OOBPairingListener {
        fun onPairingCodeGenerated(code: String)
        fun onDeviceDiscovered(device: BluetoothDevice, pairingCode: String)
        fun onPairingStarted(device: BluetoothDevice)
        fun onPairingCompleted(device: BluetoothDevice)
        fun onPairingFailed(error: String)
        fun onAdvertisingStarted()
        fun onAdvertisingStopped()
        fun onScanStarted()
        fun onScanStopped()
        fun onWaitingForRetry(remainingSeconds: Int) // 新增：等待重试提示
        fun onBluetoothConnectionRequested(device: BluetoothDevice) // 新增：请求建立蓝牙连接
    }
    
    private var pairingListener: OOBPairingListener? = null
    
    // 蓝牙广播回调
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            // ✅ 优化4: 日志分级 - 使用 Log.v() 避免 release 版本性能损耗
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "BLE广告启动成功")
            }
            isAdvertising = true
            pairingListener?.onAdvertisingStarted()
        }
        
        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            val errorMessage = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "广告数据太大"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广告商太多"
                ADVERTISE_FAILED_ALREADY_STARTED -> "广告已启动"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "内部错误"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "功能不支持"
                else -> "未知错误: $errorCode"
            }
            Log.e(TAG, "BLE广告启动失败: $errorMessage")
            isAdvertising = false
            pairingListener?.onPairingFailed("广告启动失败: $errorMessage")
        }
    }
    
    // BLE扫描回调
    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val device = result.device
            val scanRecord = result.scanRecord

            // 检查是否包含OOB服务UUID
            scanRecord?.serviceUuids?.forEach { parcelUuid ->
                if (parcelUuid == OOB_SERVICE_UUID) {
                    // 从广告数据中提取配对码
                    val pairingCode = extractPairingCodeFromScanRecord(scanRecord)
                    if (pairingCode != null) {
                        val deviceName = if (hasBluetoothPermissions()) {
                            device.name ?: "Unknown"
                        } else {
                            "Unknown"
                        }
                        
                        // 检查设备配对状态
                        val bondState = device.bondState
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "发现OOB配对设备: $deviceName, 配对码: $pairingCode, 配对状态: $bondState")
                        }
                        
                        discoveredDevice = device
                        pairingListener?.onDeviceDiscovered(device, pairingCode)

                        // ✅ 优化2: 找到设备后立即停止扫描，释放资源
                        stopScanning()
                        
                        // 如果设备已配对，直接触发连接流程
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "设备已配对，自动触发连接流程")
                            }
                            // 延迟一小段时间确保扫描完全停止
                            handler.postDelayed({
                                pairingListener?.onPairingCompleted(device)
                                pairingListener?.onBluetoothConnectionRequested(device)
                            }, 500L)
                        }
                    }
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "扫描已启动"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "功能不支持"
                else -> "未知错误: $errorCode"
            }
            Log.e(TAG, "BLE扫描失败: $errorMessage")
            pairingListener?.onPairingFailed("扫描失败: $errorMessage")
        }
    }
    
    // 蓝牙配对状态广播接收器
    @SuppressLint("MissingPermission")
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    
                    device?.let {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "配对状态变化: ${it.name} - 从$previousState 到 $state")
                        }
                        
                        when (state) {
                            BluetoothDevice.BOND_BONDING -> {
                                // 清除任何等待状态
                                isWaitingForRetry = false
                                handler.removeCallbacksAndMessages(null)
                                pairingListener?.onPairingStarted(it)
                            }
                            BluetoothDevice.BOND_BONDED -> {
                                // 配对成功，重置尝试计数和等待状态
                                pairingAttemptCount = 0
                                isWaitingForRetry = false
                                handler.removeCallbacksAndMessages(null)
                                pairingListener?.onPairingCompleted(it)
                                // 配对成功后请求建立蓝牙连接
                                pairingListener?.onBluetoothConnectionRequested(it)
                                stopAdvertising() // 配对完成后停止广告
                            }
                            BluetoothDevice.BOND_NONE -> {
                                if (previousState == BluetoothDevice.BOND_BONDING) {
                                    // 配对失败，增加尝试计数
                                    pairingAttemptCount++
                                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                        Log.v(TAG, "配对失败，当前尝试次数: $pairingAttemptCount/$MAX_PAIRING_ATTEMPTS")
                                    }
                                    
                                    if (hasReachedMaxPairingAttempts()) {
                                        pairingListener?.onPairingFailed("配对失败次数过多，请重新开始配对")
                                        // 达到最大尝试次数，自动重置状态
                                        resetPairingState()
                                    } else {
                                        // 启动5秒等待机制
                                        startWaitingForRetry(device)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 初始化OOB配对管理器
     */
    fun initialize() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "设备不支持蓝牙")
            return
        }
        if (bluetoothLeAdvertiser == null) {
            Log.w(TAG, "bluetoothLeAdvertiser == null，确认权限或蓝牙是否打开")
        }
        
        // 检查BLE广告和扫描支持
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "设备不支持BLE")
            return
        }
        
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        
        if (bluetoothLeAdvertiser == null) {
            Log.w(TAG, "设备不支持BLE广告")
        }
        
        if (bluetoothLeScanner == null) {
            Log.w(TAG, "设备不支持BLE扫描")
        }
        
        // 注册配对状态广播接收器
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(context, pairingReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        Log.d(TAG, "蓝牙OOB配对管理器初始化完成")
        Log.d(TAG, """
    -------------------------------------------------
    蓝牙已打开        : ${bluetoothAdapter?.isEnabled}
    支持BLE           : ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}
    支持多重广告      : ${bluetoothAdapter?.isMultipleAdvertisementSupported}
    advertiser 实例   : $bluetoothLeAdvertiser
    权限 SCAN         : ${context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED}
    权限 ADVERTISE    : ${context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED}
    -------------------------------------------------
""".trimIndent())
    }
    
    /**
     * 开始OOB配对过程（作为发起方）
     */
    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    ])
    fun startOOBPairing() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法开始OOB配对")
            pairingListener?.onPairingFailed("缺少蓝牙权限")
            return
        }
        
        // 生成配对码
        pairingCode = generatePairingCode()
        pairingListener?.onPairingCodeGenerated(pairingCode!!)
        
        // 开始BLE广告
        startAdvertising()
        
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "OOB配对已启动，配对码: $pairingCode")
        }
    }
    
    /**
     * 开始扫描OOB配对设备（作为响应方）
     */
    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    ])
    fun startScanningForOOBDevices() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法开始扫描")
            pairingListener?.onPairingFailed("缺少蓝牙权限")
            return
        }
        
        if (isScanning) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "已经在扫描中")
            }
            return
        }
        
        val scanner = bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BLE扫描器不可用")
            pairingListener?.onPairingFailed("BLE扫描器不可用")
            return
        }
        
        // 创建扫描过滤器，只扫描包含OOB服务UUID的设备
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(OOB_SERVICE_UUID)
                .build()
        )
        
        // 扫描设置
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        
        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            pairingListener?.onScanStarted()
            
            // 设置扫描超时
            android.os.Handler(context.mainLooper).postDelayed({
                if (isScanning) {
                    Log.d(TAG, "扫描超时，未找到OOB配对设备")
                    stopScanning()
                    pairingListener?.onPairingFailed("扫描超时，未找到配对设备")
                }
            }, SCAN_TIMEOUT_MS)
            
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "开始扫描OOB配对设备")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "权限被拒绝，无法开始扫描", e)
            pairingListener?.onPairingFailed("权限被拒绝，无法扫描")
        } catch (e: Exception) {
            Log.e(TAG, "开始扫描失败", e)
            pairingListener?.onPairingFailed("开始扫描失败: ${e.message}")
        }
    }
    
    /**
     * 使用配对码连接到发现的设备
     */
    @SuppressLint("MissingPermission")
    fun connectWithPairingCode(pairingCode: String) {
        val device = discoveredDevice
        if (device == null) {
            Log.e(TAG, "未发现设备，无法连接")
            pairingListener?.onPairingFailed("未发现设备")
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "缺少蓝牙权限，无法连接设备")
            pairingListener?.onPairingFailed("缺少蓝牙权限")
            return
        }
        
        // 验证配对码（在实际应用中应该更复杂的验证）
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "使用配对码连接设备: ${device.name}, 配对码: $pairingCode")
        }
        
        // 检查设备是否已经配对
        val bondState = device.bondState
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "设备配对状态: $bondState (BONDED=${BluetoothDevice.BOND_BONDED})")
        }
        
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                // 设备已经配对，直接触发配对完成回调
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "设备已配对，跳过配对流程，直接建立连接")
                }
                pairingListener?.onPairingCompleted(device)
                pairingListener?.onBluetoothConnectionRequested(device)
                stopAdvertising() // 停止广告
            }
            BluetoothDevice.BOND_BONDING -> {
                // 设备正在配对中，等待配对完成
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "设备正在配对中，等待配对完成")
                }
                pairingListener?.onPairingStarted(device)
            }
            BluetoothDevice.BOND_NONE -> {
                // 设备未配对，开始配对过程
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "设备未配对，开始配对流程")
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        val result = device.createBond()
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "createBond() 返回: $result")
                        }
                        if (!result) {
                            Log.e(TAG, "createBond() 返回false，配对可能失败")
                            pairingListener?.onPairingFailed("无法启动配对流程")
                        }
                    } else {
                        // 对于旧版本Android，使用反射或其他方法
                        Log.w(TAG, "Android版本过低，可能无法自动配对")
                        pairingListener?.onPairingFailed("Android版本过低，请手动配对")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "权限被拒绝，无法创建配对", e)
                    pairingListener?.onPairingFailed("权限被拒绝，无法配对")
                } catch (e: Exception) {
                    Log.e(TAG, "配对失败", e)
                    pairingListener?.onPairingFailed("配对失败: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 停止所有OOB配对活动
     */
    fun stopOOBPairing() {
        stopAdvertising()
        stopScanning()
        discoveredDevice = null
        pairingCode = null
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "OOB配对已停止")
        }
    }
    
    /**
     * 设置配对监听器
     */
    fun setPairingListener(listener: OOBPairingListener) {
        this.pairingListener = listener
    }
    
    /**
     * 获取当前配对码
     */
    fun getCurrentPairingCode(): String? {
        return pairingCode
    }
    
    /**
     * 获取发现的设备
     */
    fun getDiscoveredDevice(): BluetoothDevice? {
        return discoveredDevice
    }
    
    /**
     * 重置配对状态，解决配对重试限制问题
     * ✅ 增强：更彻底的状态重置，包括 null 检查和回调清理
     */
    fun resetPairingState() {
        Log.d(TAG, "🔄 开始重置配对状态...")
        
        // 重置计数器和等待状态
        pairingAttemptCount = 0
        isWaitingForRetry = false
        
        // 清除所有待处理的回调
        handler.removeCallbacksAndMessages(null)
        
        // 停止广告和扫描（带 null 检查）
        try {
            if (isAdvertising) {
                stopAdvertising()
            }
        } catch (e: Exception) {
            Log.w(TAG, "停止广告时出错", e)
        }
        
        try {
            if (isScanning) {
                stopScanning()
            }
        } catch (e: Exception) {
            Log.w(TAG, "停止扫描时出错", e)
        }
        
        // 清空设备和配对码引用
        discoveredDevice = null
        pairingCode = null
        
        Log.d(TAG, "✅ 配对状态已完全重置")
    }
    
    /**
     * 获取当前配对尝试次数
     */
    fun getPairingAttemptCount(): Int {
        return pairingAttemptCount
    }
    
    /**
     * 检查是否达到最大配对尝试次数
     */
    fun hasReachedMaxPairingAttempts(): Boolean {
        return pairingAttemptCount >= MAX_PAIRING_ATTEMPTS
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopOOBPairing()
        try {
            context.unregisterReceiver(pairingReceiver)
        } catch (e: IllegalArgumentException) {
            // 接收器可能未注册
        }
    }
    
    // 私有方法
    
    private fun startAdvertising() {
        if (isAdvertising) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "已经在广告中")
            }
            return
        }
        
        val advertiser = bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE广告商不可用")
            pairingListener?.onPairingFailed("BLE广告不可用")
            return
        }
        
        val pairingCode = this.pairingCode
        if (pairingCode == null) {
            Log.e(TAG, "配对码未生成，无法开始广告")
            pairingListener?.onPairingFailed("配对码未生成")
            return
        }
        
        // 广告设置
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(ADVERTISE_TIMEOUT_MS.toInt())
            .build()
        
        // 广告数据 - 优化数据大小
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(OOB_SERVICE_UUID)            // 16位UUID (2字节)
            .setIncludeDeviceName(false)                 // 不包含设备名以节省空间
            .build()
        
        // 扫描响应数据 - 包含配对码信息
        val scanResponseData = AdvertiseData.Builder()
            .addServiceData(OOB_SERVICE_UUID, pairingCode.toByteArray()) // 配对码 (4字节)
            .build()
        
        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "开始BLE广告，配对码: $pairingCode")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "权限被拒绝，无法开始广告", e)
            pairingListener?.onPairingFailed("权限被拒绝，无法广告")
        } catch (e: Exception) {
            Log.e(TAG, "开始广告失败", e)
            pairingListener?.onPairingFailed("开始广告失败: ${e.message}")
        }
    }
    
    private fun stopAdvertising() {
        if (isAdvertising) {
            try {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                pairingListener?.onAdvertisingStopped()
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "BLE广告已停止")
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止广告失败", e)
            }
        }
    }
    
    private fun stopScanning() {
        if (isScanning) {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                isScanning = false
                pairingListener?.onScanStopped()
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "BLE扫描已停止")
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止扫描失败", e)
            }
        }
    }

    private fun generatePairingCode(): String {
        val secureRandom = SecureRandom()
        // 0-8999 → +1000 保证 4 位数字
        return (secureRandom.nextInt(9000) + 1000).toString()
    }
    
    private fun extractPairingCodeFromScanRecord(scanRecord: android.bluetooth.le.ScanRecord): String? {
        try {
            val serviceData = scanRecord.getServiceData(OOB_SERVICE_UUID)
            return serviceData?.toString(Charset.forName("UTF-8"))
        } catch (e: Exception) {
            Log.e(TAG, "从扫描记录提取配对码失败", e)
        }
        return null
    }
    
    private fun hasBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 启动5秒等待机制，在配对失败后自动重试
     */
    private fun startWaitingForRetry(device: BluetoothDevice) {
        if (isWaitingForRetry) {
            Log.d(TAG, "已经在等待重试中，跳过")
            return
        }
        
        isWaitingForRetry = true
        var remainingSeconds = 5
        
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "启动5秒等待机制，将在${remainingSeconds}秒后自动重试配对")
        }
        
        // 立即通知UI开始等待
        pairingListener?.onWaitingForRetry(remainingSeconds)
        
        // 每秒更新一次剩余时间
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (isWaitingForRetry && remainingSeconds > 0) {
                    remainingSeconds--
                    pairingListener?.onWaitingForRetry(remainingSeconds)
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "等待重试，剩余时间: ${remainingSeconds}秒")
                    }
                    handler.postDelayed(this, 1000L)
                } else if (isWaitingForRetry && remainingSeconds == 0) {
                    // 5秒等待结束，自动重试配对
                    isWaitingForRetry = false
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "5秒等待结束，自动重试配对")
                    }
                    pairingListener?.onPairingFailed("正在自动重试配对...")
                    
                    // 延迟一小段时间后重新开始配对
                    handler.postDelayed({
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                                device.createBond()
                            } else {
                                Log.w(TAG, "Android版本过低，无法自动重试配对")
                                pairingListener?.onPairingFailed("Android版本过低，请手动重试")
                            }
                        } catch (e: SecurityException) {
                            Log.e(TAG, "权限被拒绝，无法重试配对", e)
                            pairingListener?.onPairingFailed("权限被拒绝，无法重试配对")
                        } catch (e: Exception) {
                            Log.e(TAG, "重试配对失败", e)
                            pairingListener?.onPairingFailed("重试配对失败: ${e.message}")
                        }
                    }, 500L) // 额外延迟500ms确保蓝牙状态稳定
                }
            }
        }
        
        // 启动倒计时
        handler.post(countdownRunnable)
    }
}
