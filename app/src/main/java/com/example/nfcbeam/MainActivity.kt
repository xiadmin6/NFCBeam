package com.example.nfcbeam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.registerForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.nfcbeam.ui.screens.HomeScreen
import com.example.nfcbeam.ui.screens.FileSelectPage
import com.example.nfcbeam.ui.screens.TransferInProgressScreen
import com.example.nfcbeam.ui.screens.TransferCompleteScreen
import com.example.nfcbeam.ui.theme.NFCBeamTheme
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally


class MainActivity : ComponentActivity(), FileTransferManager.TransferListener,
    BluetoothOOBPairingManager.OOBPairingListener {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var bluetoothOOBPairingManager: BluetoothOOBPairingManager

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initializeBluetooth()
        }
    }
    
    // 启用蓝牙请求
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            initializeBluetooth()
        }
    }
    
    // 文件选择器 - 支持多选
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    
    // 图片选择器 - 支持多选
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    // 视频选择器 - 支持多选
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    // 文件夹选择器
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            selectedFiles = listOf(it)
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    // 屏幕状态
    private var currentScreen by mutableStateOf(Screen.HOME)
    private var selectedFiles by mutableStateOf<List<Uri>>(emptyList())
    private var transferProgress by mutableStateOf(0f)
    private var transferStatus by mutableStateOf(FileTransferManager.TransferStatus())
    private var transferredFiles by mutableStateOf<List<String>>(emptyList())
    private var isTransferSuccess by mutableStateOf(true)
    private var isNfcConnected by mutableStateOf(false)
    private var bluetoothDeviceName by mutableStateOf("")
    private var isSenderMode by mutableStateOf(true)
    
    // 新增：标记配对是否完成
    private var isPairingCompleted by mutableStateOf(false)

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化管理器
        bluetoothManager = BluetoothManager(this)
        fileTransferManager = FileTransferManager(this, bluetoothManager)
        fileTransferManager.setTransferListener(this) // 设置文件传输监听器
        bluetoothOOBPairingManager = BluetoothOOBPairingManager(this)
        bluetoothOOBPairingManager.setPairingListener(this) // 设置监听器

        // 设置蓝牙状态监听器
        bluetoothManager.setStateListener(object : BluetoothManager.BluetoothStateListener {
            override fun onBluetoothStateChanged(enabled: Boolean) {
                Log.d("Bluetooth", "蓝牙状态变化: $enabled")
            }

            override fun onDeviceDiscovered(device: android.bluetooth.BluetoothDevice) {
                Log.d("Bluetooth", "发现设备: ${device.name}")
            }

            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                Log.d("Bluetooth", "蓝牙Socket连接成功: ${device.name}")
                // 蓝牙连接成功后，根据模式开始相应的传输流程
                if (isSenderMode) {
                    // 发送端：Socket连接成功后才跳转到文件选择页面
                    if (isPairingCompleted) {
                        currentScreen = Screen.FILE_SELECT
                        Log.d("Bluetooth", "发送端蓝牙Socket连接成功，跳转到文件选择页面")
                    } else {
                        Log.d("Bluetooth", "发送端Socket连接成功，但配对未完成，等待配对完成")
                    }
                } else {
                    // 接收端：连接成功后跳转到传输页面并开始接收文件
                    currentScreen = Screen.TRANSFER_IN_PROGRESS
                    transferStatus = FileTransferManager.TransferStatus(isConnecting = false, isTransferring = true)
                    fileTransferManager.startFileReceiver()
                    Log.d("Bluetooth", "接收端蓝牙连接成功，跳转到传输页面并开始接收文件")
                }
            }

            override fun onDeviceDisconnected() {
                Log.d("Bluetooth", "蓝牙连接断开")
                isNfcConnected = false
                bluetoothDeviceName = ""
                isPairingCompleted = false
            }

            override fun onConnectionFailed(error: String) {
                Log.e("Bluetooth", "蓝牙连接失败: $error")
                isNfcConnected = false
                bluetoothDeviceName = ""
                isPairingCompleted = false
            }
        })

        bluetoothOOBPairingManager.initialize()
        bluetoothOOBPairingManager.setPairingListener(this)

        setContent {
            NFCBeamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NFCBeamApp(
                        currentScreen = currentScreen,
                        transferProgress = transferProgress,
                        transferStatus = transferStatus,
                        transferredFiles = transferredFiles,
                        isSenderMode = isSenderMode,
                        isTransferSuccess = isTransferSuccess,
                        selectedFiles = selectedFiles,
                        bluetoothDeviceName = bluetoothDeviceName,
                        isNfcConnected = isNfcConnected,
                        onScreenChange = { screen -> currentScreen = screen },
                        onFilesSelected = { files ->
                            selectedFiles = files
                            // 立即启动文件传输
                            startTransfer()
                        },
                        onTransferStart = { startTransfer() },
                        onBackToHome = {
                            resetTransferState()
                            currentScreen = Screen.HOME
                        },
                        onRetryTransfer = { startTransfer() },
                        onRequestPermissions = { requestPermissions() },
                        onPhotoPicker = {
                            imagePickerLauncher.launch(arrayOf("image/*"))
                        },
                        onVideoPicker = {
                            videoPickerLauncher.launch(arrayOf("video/*"))
                        },
                        onFilePicker = { mimeTypes ->
                            filePickerLauncher.launch(mimeTypes)
                        },
                        onFolderPicker = {
                            folderPickerLauncher.launch(null)
                        },
                        onToggleMode = {          // ← 这里才是真正实现
                            toggleMode()
                        },
                        onNfcTouch = { handleNfcTouch() },
                        onFileSelectionChange = { files -> selectedFiles = files },
                        onCancelTransfer = { fileTransferManager.cancelTransfer() },
                        onTransferComplete = { success -> 
                            isTransferSuccess = success
                            currentScreen = Screen.TRANSFER_COMPLETE
                        }
                    )
                }
            }
        }

        // 请求权限
        requestPermissions()

        // 由于已改用蓝牙OOB配对机制，不再需要处理NFC Intent
        // 保留NFC初始化以兼容现有功能

        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.HOME -> finish()                 // 退出 App
                    else        -> {
                        resetTransferState()
                        currentScreen = Screen.HOME        // 回首页
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        // 蓝牙权限
        permissions.add(Manifest.permission.BLUETOOTH)
        permissions.add(Manifest.permission.BLUETOOTH_ADMIN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE      // ← 广告商需要的权限
            )
        }

        // 位置权限（蓝牙扫描需要）
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        // 存储权限
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        
        // Android 13+ 媒体权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            initializeBluetooth()
        }
    }
    

    private fun initializeBluetooth() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e("Bluetooth", "设备不支持蓝牙")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            bluetoothManager.initialize()
            // ↓↓↓  蓝牙已打开，再初始化一次，防止权限延迟  ↓↓↓
            bluetoothOOBPairingManager.initialize()
            updateModeConfiguration()
        }
    }
    
    // 切换发送/接收模式
    fun toggleMode() {
        isSenderMode = !isSenderMode
        isPairingCompleted = false // 重置配对状态
        updateModeConfiguration()
        Log.d("Mode", "模式切换为: ${if (isSenderMode) "发送端" else "接收端"}")
    }
    
    // 更新模式配置
    @SuppressLint("MissingPermission")
    private fun updateModeConfiguration() {
        // 重置配对状态，解决配对重试限制问题
        bluetoothOOBPairingManager.resetPairingState()
        isPairingCompleted = false
        
        if (isSenderMode) {
            // 发送端模式：停止蓝牙服务器，开始BLE扫描
            bluetoothManager.stopBluetoothServer()
            bluetoothOOBPairingManager.startScanningForOOBDevices()
            Log.d("Mode", "发送端模式，开始BLE扫描")
        } else {
            // 接收端模式：启动蓝牙服务器，开始BLE广告
            bluetoothManager.startBluetoothServer()
            bluetoothOOBPairingManager.startOOBPairing()
            Log.d("Mode", "接收端模式，启动蓝牙服务器和BLE广告")
        }
    }
    
    // 以下NFC传输蓝牙信息相关方法已被蓝牙OOB配对机制替代
    // 不再需要这些方法
    
    // 处理NFC触碰事件 - 现在使用蓝牙OOB配对
    @SuppressLint("MissingPermission")
    fun handleNfcTouch() {
        // 手动配对模式
        if (isSenderMode) {
            // 发送端：开始BLE扫描寻找接收端设备
            bluetoothOOBPairingManager.startScanningForOOBDevices()
            Log.d("OOBPairing", "发送端开始BLE扫描寻找接收端设备")
        } else {
            // 接收端：开始BLE广告等待发送端连接
            bluetoothOOBPairingManager.startOOBPairing()
            Log.d("OOBPairing", "接收端开始BLE广告等待发送端连接")
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 由于已改用蓝牙OOB配对机制，不再需要处理NFC Intent
        Log.d("NFC", "接收到NFC Intent，但已改用蓝牙OOB配对机制")
    }
    
    override fun onResume() {
        super.onResume()

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("OOBPairing", "❌ BLUETOOTH_CONNECT 未授予，无法接收配对广播")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要蓝牙")
                .setMessage("必须打开蓝牙才能使用 OOB 配对")
                .setPositiveButton("去打开") { _, _ ->
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
                .setNegativeButton("退出") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }
        updateModeConfiguration()
    }
    
    override fun onPause() {
        super.onPause()
//        if (::nfcAdapter.isInitialized && nfcAdapter != null) {
//            nfcAdapter.disableForegroundDispatch(this)
//        }
    }
    
    private fun startTransfer() {
        if (selectedFiles.isEmpty()) return
        
        currentScreen = Screen.TRANSFER_IN_PROGRESS
        transferStatus = FileTransferManager.TransferStatus(isConnecting = true)
        
        // 启动文件传输
        fileTransferManager.startFileTransfer(selectedFiles)
    }
    
    private fun resetTransferState() {
        selectedFiles = emptyList()
        transferProgress = 0f
        transferStatus = FileTransferManager.TransferStatus()
        transferredFiles = emptyList()
        isTransferSuccess = true
        isPairingCompleted = false
    }
    
    // 以下方法已被蓝牙OOB配对机制替代，不再需要
    // sendNfcData() 和 getBluetoothInfoForNfc() 方法已移除

    // TransferListener接口实现
    override fun onTransferStarted(totalFiles: Int, totalSize: Long) {
        Log.d("Transfer", "传输开始: 文件数=$totalFiles, 总大小=$totalSize")
        transferStatus = FileTransferManager.TransferStatus(
            isTransferring = true,
            totalFiles = totalFiles
        )
        transferProgress = 0f
    }

    override fun onFileTransferStarted(fileInfo: FileTransferManager.FileInfo, currentFile: Int, totalFiles: Int) {
        Log.d("Transfer", "文件传输开始: ${fileInfo.fileName}, 当前文件: $currentFile/$totalFiles")
        transferStatus = FileTransferManager.TransferStatus(
            isTransferring = true,
            totalFiles = totalFiles,
            transferredFiles = currentFile - 1,
            currentFileName = fileInfo.fileName
        )
    }

    override fun onTransferProgress(transferredBytes: Long, totalBytes: Long, currentFile: Int, totalFiles: Int) {
        val progress = if (totalBytes > 0) {
            (transferredBytes.toFloat() / totalBytes.toFloat())
        } else {
            0f
        }
        transferProgress = progress * 100f
        transferStatus = FileTransferManager.TransferStatus(
            isTransferring = true,
            progress = progress,
            totalFiles = totalFiles,
            transferredFiles = currentFile - 1
        )
        Log.d("Transfer", "传输进度: ${(progress * 100).toInt()}%, 文件: $currentFile/$totalFiles, 已传输: $transferredBytes/$totalBytes")
    }

    override fun onTransferCompleted() {
        Log.d("Transfer", "传输完成")
        transferStatus = FileTransferManager.TransferStatus(
            isCompleted = true,
            isSuccess = true,
            progress = 1f,
            totalFiles = selectedFiles.size,
            transferredFiles = selectedFiles.size
        )
        transferProgress = 100f
        isTransferSuccess = true
        currentScreen = Screen.TRANSFER_COMPLETE
        
        // 更新传输完成的文件列表
        transferredFiles = selectedFiles.map { uri ->
            uri.lastPathSegment ?: "未知文件"
        }
    }

    override fun onTransferError(error: String) {
        Log.e("Transfer", "传输错误: $error")
        transferStatus = FileTransferManager.TransferStatus(
            isCompleted = true,
            isSuccess = false,
            errorMessage = error
        )
        isTransferSuccess = false
        currentScreen = Screen.TRANSFER_COMPLETE
    }

    override fun onTransferCancelled() {
        Log.d("Transfer", "传输取消")
        transferStatus = FileTransferManager.TransferStatus()
        transferProgress = 0f
        currentScreen = Screen.HOME
    }

    /* ================  OOBPairingListener 接口实现 ================ */
    override fun onPairingCodeGenerated(code: String) {
        Log.d("OOBPairing", "配对码生成: $code")
    }

    @SuppressLint("MissingPermission")
    override fun onDeviceDiscovered(device: android.bluetooth.BluetoothDevice, pairingCode: String) {
        Log.d("OOBPairing", "发现设备: ${device.name ?: "Unknown"}, 配对码: $pairingCode")
        // 自动使用配对码连接设备
        bluetoothOOBPairingManager.connectWithPairingCode(pairingCode)
    }

    @SuppressLint("MissingPermission")
    override fun onPairingStarted(device: android.bluetooth.BluetoothDevice) {
        Log.d("OOBPairing", "蓝牙OOB配对开始: ${device.name ?: "Unknown"}")
    }

    @SuppressLint("MissingPermission")
    override fun onPairingCompleted(device: BluetoothDevice) {
        val connectGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        Log.d("OOBPairing", "BLUETOOTH_CONNECT granted: $connectGranted")

        // 获取设备名称（需要权限检查）
        val deviceName = if (connectGranted) {
            device.name ?: "Unknown Device"
        } else {
            "Unknown Device"
        }
        
        // 更新设备名称状态和配对完成标记
        bluetoothDeviceName = deviceName
        isPairingCompleted = true
        Log.d("OOBPairing", "配对完成 device.name: $deviceName, address: ${device.address}")
        
        // 配对完成后不立即跳转，等待Socket连接建立
        // Socket连接建立后会在 onDeviceConnected 回调中跳转
        if (isSenderMode) {
            Log.d("OOBPairing", "发送端配对完成，等待Socket连接建立后跳转至文件选择页面")
        } else {
            Log.d("OOBPairing", "接收端配对完成，等待Socket连接建立")
        }
    }

    override fun onPairingFailed(error: String) {
        Log.e("OOBPairing", "配对失败: $error")
        isPairingCompleted = false
    }

    override fun onAdvertisingStarted() {
        Log.d("OOBPairing", "BLE广告已启动")
    }

    override fun onAdvertisingStopped() {
        Log.d("OOBPairing", "BLE广告已停止")
    }

    override fun onScanStarted() {
        Log.d("OOBPairing", "BLE扫描已启动")
    }

    override fun onScanStopped() {
        Log.d("OOBPairing", "BLE扫描已停止")
    }

    override fun onWaitingForRetry(remainingSeconds: Int) {
        Log.d("OOBPairing", "等待重试配对，剩余时间: ${remainingSeconds}秒")
        // 这里可以添加UI提示逻辑，比如显示倒计时提示
        // 在实际应用中，应该更新UI状态来显示等待提示
        if (remainingSeconds > 0) {
            // 显示等待提示
            Log.i("OOBPairing", "配对失败，等待${remainingSeconds}秒后自动重试...")
        } else {
            // 等待结束，开始重试
            Log.i("OOBPairing", "等待结束，正在自动重试配对...")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onBluetoothConnectionRequested(device: android.bluetooth.BluetoothDevice) {
        // 获取设备名称（需要权限检查）
        val connectGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        
        val deviceName = if (connectGranted) {
            device.name ?: "Unknown Device"
        } else {
            "Unknown Device"
        }
        
        Log.d("OOBPairing", "配对成功，请求建立蓝牙Socket连接: $deviceName / ${device.address}")
        
        // 更新连接状态和设备名称
        isNfcConnected = true
        bluetoothDeviceName = deviceName
        
        // 建立实际的蓝牙Socket连接
        if (isSenderMode) {
            // 发送端：连接到接收端设备
            bluetoothManager.connectToDevice(device.address)
            Log.d("OOBPairing", "发送端正在建立Socket连接到设备: ${device.address}")
        } else {
            // 接收端：设备已经作为服务器运行，等待连接
            // 注意：服务器应该已经在 updateModeConfiguration() 中启动
            Log.d("OOBPairing", "接收端已启动服务器，等待发送端Socket连接")
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NFCBeamApp(
    currentScreen: Screen,
    transferProgress: Float,
    transferStatus: FileTransferManager.TransferStatus,
    transferredFiles: List<String>,
    isSenderMode: Boolean,
    isTransferSuccess: Boolean,
    bluetoothDeviceName: String,
    isNfcConnected: Boolean,
    onScreenChange: (Screen) -> Unit,
    onFilesSelected: (List<android.net.Uri>) -> Unit,
    onTransferStart: () -> Unit,
    onBackToHome: () -> Unit,
    onRetryTransfer: () -> Unit,
    onRequestPermissions: () -> Unit,
    selectedFiles: List<Uri>,
    onPhotoPicker: () -> Unit,
    onVideoPicker: () -> Unit,
    onFilePicker: (Array<String>) -> Unit,
    onFolderPicker: () -> Unit,
    onNfcTouch: () -> Unit,
    onFileSelectionChange: (List<android.net.Uri>) -> Unit,
    onCancelTransfer: () -> Unit,
    onTransferComplete: (Boolean) -> Unit,
    onToggleMode: () -> Unit,
) {
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                slideInHorizontally { width -> width } + fadeIn() with
                        slideOutHorizontally { width -> -width } + fadeOut()
            } else {
                slideInHorizontally { width -> -width } + fadeIn() with
                        slideOutHorizontally { width -> width } + fadeOut()
            }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                onSendFiles = { onScreenChange(Screen.FILE_SELECT) },
                onReceiveFiles = { onScreenChange(Screen.TRANSFER_IN_PROGRESS) },
                onToggleMode = { onToggleMode() },
                onBluetoothPairing = { onNfcTouch() },
                isNfcConnected = isNfcConnected,
                isSenderMode = isSenderMode
            )
            Screen.FILE_SELECT -> FileSelectPage(
                bluetoothDeviceName = bluetoothDeviceName,
                selectedFiles = selectedFiles, // 使用实际的状态变量
                onPhotoPicker = onPhotoPicker,
                onVideoPicker = onVideoPicker,
                onFilePicker = onFilePicker,
                onFolderPicker = onFolderPicker,
                onSend = onFilesSelected,
                onFileSelectionChange = onFileSelectionChange
            )
            Screen.TRANSFER_IN_PROGRESS -> TransferInProgressScreen(
                transferStatus = transferStatus,
                onCancel = { 
                    onCancelTransfer()
                    onBackToHome()
                },
                onTransferComplete = onTransferComplete
            )
            Screen.TRANSFER_COMPLETE -> TransferCompleteScreen(
                isSuccess = isTransferSuccess,
                transferredFiles = transferredFiles,
                totalSize = "0 MB", // 这里可以添加实际计算文件大小的逻辑
                onBackToHome = onBackToHome,
                onRetry = onRetryTransfer
            )
        }
    }
}

enum class Screen {
    HOME,
    FILE_SELECT,
    TRANSFER_IN_PROGRESS,
    TRANSFER_COMPLETE
}
