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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.nfcbeam.ui.screens.HomeScreen
import com.example.nfcbeam.ui.screens.FileSelectPage
import com.example.nfcbeam.ui.screens.TransferInProgressScreen
import com.example.nfcbeam.ui.screens.TransferCompleteScreen
import com.example.nfcbeam.ui.theme.NFCBeamTheme
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.MainScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : ComponentActivity(), FileTransferManager.TransferListener,
    BluetoothOOBPairingManager.OOBPairingListener {

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var bluetoothOOBPairingManager: BluetoothOOBPairingManager

    private val mainScope = MainScope()

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
    
    // 文件选择器 - 支持多选，追加到已选文件列表
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 追加新选择的文件到已有列表，去重
            val newFiles = (selectedFiles + uris).distinct()
            selectedFiles = newFiles
            // 保持在文件选择页面
            if (currentScreen != Screen.FILE_SELECT) {
                currentScreen = Screen.FILE_SELECT
            }
        }
    }
    
    
    // 图片选择器 - 支持多选（已废弃，现在使用内置网格选择）
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 追加新选择的文件到已有列表，去重
            val newFiles = (selectedFiles + uris).distinct()
            selectedFiles = newFiles
            currentScreen = Screen.FILE_SELECT
        }
    }
    
    // 视频选择器 - 支持多选（已废弃，现在使用内置网格选择）
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            // 追加新选择的文件到已有列表，去重
            val newFiles = (selectedFiles + uris).distinct()
            selectedFiles = newFiles
            currentScreen = Screen.FILE_SELECT
        }
    }
    
    // 文件夹选择器 - 追加到已选文件列表
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 系统固定支持这两个 flag，不需要从 Intent 取
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            try {
                contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: SecurityException) {
                Log.e("FolderPicker", "无法获取持久化权限", e)
                Toast.makeText(this@MainActivity, "权限授予失败", Toast.LENGTH_SHORT).show()
                return@let
            }

            // 后台展开
            mainScope.launch(Dispatchers.IO) {
                val childFiles = fileTransferManager.listFilesInDirectory(it)
                withContext(Dispatchers.Main) {
                    if (childFiles.isNotEmpty()) {
                        selectedFiles = (selectedFiles + childFiles).distinct()
                        currentScreen = Screen.FILE_SELECT
                    } else {
                        Toast.makeText(this@MainActivity, "文件夹为空", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    // 🆕 自定义下载目录选择器
    private val customDownloadFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // 获取持久化权限
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            try {
                contentResolver.takePersistableUriPermission(it, takeFlags)
                // 设置自定义下载路径
                downloadPathManager.setCustomDownloadPath(it)
                currentDownloadLocation = DownloadPathManager.Companion.DownloadLocation.CUSTOM
                
                Toast.makeText(
                    this@MainActivity,
                    "自定义下载目录已设置: ${downloadPathManager.getCustomPathDisplayName()}",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: SecurityException) {
                Log.e("CustomDownloadFolder", "无法获取持久化权限", e)
                Toast.makeText(this@MainActivity, "权限授予失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // 屏幕状态
    private var currentScreen by mutableStateOf(Screen.HOME)
    private var selectedFiles by mutableStateOf<List<Uri>>(emptyList())
    private var transferProgress by mutableStateOf(0f)
    private var transferStatus by mutableStateOf(FileTransferManager.TransferStatus())
    private var transferredFiles by mutableStateOf<List<String>>(emptyList())
    private var transferredFileInfos by mutableStateOf<List<FileTransferManager.FileInfo>>(emptyList())
    private var totalTransferSize by mutableStateOf(0L)
    private var isTransferSuccess by mutableStateOf(true)
    private var isNfcConnected by mutableStateOf(false)
    private var bluetoothDeviceName by mutableStateOf("")
    private var isSenderMode by mutableStateOf(true)

    private var hasTransferResultBeenFinalized = false
    
    // 新增：标记配对是否完成
    private var isPairingCompleted by mutableStateOf(false)
    
    // 下载路径管理
    private lateinit var downloadPathManager: DownloadPathManager
    private var currentDownloadLocation by mutableStateOf(DownloadPathManager.Companion.DownloadLocation.DOWNLOADS)

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val serviceManager = ServiceConnectionManager(this)
        serviceManager.startService()

        // 初始化管理器
        bluetoothManager = BluetoothManager(this)
        fileTransferManager = FileTransferManager(this, bluetoothManager)
        fileTransferManager.setTransferListener(this) // 设置文件传输监听器
        bluetoothOOBPairingManager = BluetoothOOBPairingManager(this)
        bluetoothOOBPairingManager.setPairingListener(this) // 设置监听器
        
        // 初始化下载路径管理器
        downloadPathManager = DownloadPathManager(this)
        currentDownloadLocation = downloadPathManager.getCurrentLocation()

        // 设置蓝牙状态监听器
        bluetoothManager.setStateListener(object : BluetoothManager.BluetoothStateListener {
            override fun onBluetoothStateChanged(enabled: Boolean) {
                Log.d("Bluetooth", "蓝牙状态变化: $enabled")
            }

            override fun onDeviceDiscovered(device: android.bluetooth.BluetoothDevice) {
                Log.d("Bluetooth", "发现设备: ${device.name}")
            }

            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                Log.d("Bluetooth", "✅ 蓝牙Socket连接成功: ${device.name}")
                
                // 重要：只有在 Socket 真正连接成功后，才设置 isNfcConnected = true
                // 这样 HomeScreen 的 LaunchedEffect 才会触发自动跳转
                isNfcConnected = true
                
                // 蓝牙连接成功后，根据模式开始相应的传输流程
                if (isSenderMode) {
                    // 发送端：Socket连接成功后，HomeScreen 会自动跳转到文件选择页面
                    Log.d("Bluetooth", "发送端蓝牙Socket连接成功，等待自动跳转到文件选择页面")
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
                        transferredFileInfos = transferredFileInfos,
                        totalTransferSize = totalTransferSize,
                        isSenderMode = isSenderMode,
                        isTransferSuccess = isTransferSuccess,
                        selectedFiles = selectedFiles,
                        bluetoothDeviceName = bluetoothDeviceName,
                        isNfcConnected = isNfcConnected,
                        currentDownloadLocation = currentDownloadLocation,
                        customPathDisplayName = downloadPathManager.getCustomPathDisplayName(),
                        onCustomFolderPicker = { customDownloadFolderPickerLauncher.launch(null) },
                        onDownloadLocationChange = { location ->
                            currentDownloadLocation = location
                            downloadPathManager.setDownloadPath(location)
                            Toast.makeText(
                                this@MainActivity,
                                "下载目录已设置为: ${location.displayName}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
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
                            // ✅ 只在跳转到 HOME 时才启动配对
                            updateModeConfiguration()
                        },
                        onRetryTransfer = { startTransfer() },
                        onRequestPermissions = { requestPermissions() },
                        onPhotoPicker = {
                            imagePickerLauncher.launch(arrayOf("image/*"))
                        },
                        onVideoPicker = {
                            videoPickerLauncher.launch(arrayOf("video/*"))
                        },
                        onFilePicker = { _ ->
                            filePickerLauncher.launch("*/*")
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
    // ✅ 修复：添加延迟启动和安全检查，避免状态冲突
    @SuppressLint("MissingPermission")
    private fun updateModeConfiguration() {
        Log.d("Mode", "🔄 准备更新模式配置...")
        
        // 重置配对状态，解决配对重试限制问题
        bluetoothOOBPairingManager.resetPairingState()
        isPairingCompleted = false
        
        // ✅ 延迟 500ms 启动，确保之前的状态已完全清理
        mainScope.launch {
            kotlinx.coroutines.delay(500)
            
            // ✅ 安全检查：确保蓝牙已启用且有权限
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Log.w("Mode", "⚠️ 蓝牙未启用，跳过模式配置")
                return@launch
            }
            
            if (!hasBluetoothPermissions()) {
                Log.w("Mode", "⚠️ 缺少蓝牙权限，跳过模式配置")
                return@launch
            }
            
            // ✅ 再次检查是否在 HOME 页面，避免在其他页面启动配对
            if (currentScreen != Screen.HOME) {
                Log.d("Mode", "⚠️ 不在 HOME 页面，跳过模式配置")
                return@launch
            }
            
            if (isSenderMode) {
                // 发送端模式：停止蓝牙服务器，开始BLE扫描
                bluetoothManager.stopBluetoothServer()
                bluetoothOOBPairingManager.startScanningForOOBDevices()
                Log.d("Mode", "✅ 发送端模式已启动，开始BLE扫描")
            } else {
                // 接收端模式：启动蓝牙服务器，开始BLE广告
                bluetoothManager.startBluetoothServer()
                bluetoothOOBPairingManager.startOOBPairing()
                Log.d("Mode", "✅ 接收端模式已启动，蓝牙服务器和BLE广告")
            }
        }
    }
    
    /**
     * ✅ 新增：检查是否有蓝牙权限
     */
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
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
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
    }
    
    private fun startTransfer() {
        if (selectedFiles.isEmpty()) return
        
        currentScreen = Screen.TRANSFER_IN_PROGRESS
        transferStatus = FileTransferManager.TransferStatus(isConnecting = true)
        
        // 启动文件传输
        fileTransferManager.startFileTransfer(selectedFiles)
    }
    
    /**
     * ✅ 修复：只重置状态，不启动配对
     * 配对启动应该由页面跳转逻辑控制
     */
    private fun resetTransferState() {
        Log.d("MainActivity", "🔄 重置传输状态...")
        
        // 重置文件传输相关状态
        selectedFiles = emptyList()
        transferProgress = 0f
        transferStatus = FileTransferManager.TransferStatus()
        transferredFiles = emptyList()
        transferredFileInfos = emptyList()
        totalTransferSize = 0L
        isTransferSuccess = true
        
        // 重置蓝牙连接状态
        isPairingCompleted = false
        isNfcConnected = false
        bluetoothDeviceName = ""
        
        // 断开蓝牙连接
        bluetoothManager.disconnect()
        
        // 重置配对状态（但不启动新的配对）
        bluetoothOOBPairingManager.resetPairingState()
        hasTransferResultBeenFinalized = false
        
        Log.d("MainActivity", "✅ 传输状态已重置（未启动配对）")
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

    override fun onTransferCompleted(transferredFileList: List<FileTransferManager.FileInfo>, totalSize: Long) {
        Log.d("Transfer", "传输完成: ${transferredFileList.size} 个文件, 总大小: $totalSize 字节")

        if (!hasTransferResultBeenFinalized) {
            hasTransferResultBeenFinalized = true

            transferStatus = FileTransferManager.TransferStatus(
                isCompleted = true,
                isSuccess = true,
                progress = 1f,
                totalFiles = transferredFileList.size,
                transferredFiles = transferredFileList.size
            )
            transferProgress = 100f
            isTransferSuccess = true

            // ✅ 修复：传输完成后断开蓝牙连接，确保下次可以重新连接
            isNfcConnected = false
            bluetoothManager.disconnect()
            Log.d("Transfer", "✅ 传输完成，已断开蓝牙连接")

            transferredFileInfos = transferredFileList
            totalTransferSize = totalSize

            fileTransferManager.clearReceiveState()

            transferredFiles = transferredFileList.map { it.fileName }
            currentScreen = Screen.TRANSFER_COMPLETE
        } else {
            Log.w("Transfer", "⚠️ 传输完成回调被忽略，结果已 finalized")
        }
    }

    override fun onTransferError(error: String) {
        Log.e("Transfer", "传输错误: $error")

        // ✅ 修复：只有当结果尚未确定时才接受错误状态
        if (!hasTransferResultBeenFinalized) {
            hasTransferResultBeenFinalized = true // 🔒 锁定结果

            transferStatus = FileTransferManager.TransferStatus(
                isCompleted = true,
                isSuccess = false,
                errorMessage = error
            )
            isTransferSuccess = false

            fileTransferManager.clearReceiveState()

            isNfcConnected = false
            bluetoothManager.disconnect()
            Log.d("Transfer", "✅ 传输错误，已断开蓝牙连接")

            currentScreen = Screen.TRANSFER_COMPLETE
        } else {
            // 成功已上报，忽略后续清理阶段的非致命错误
            Log.w("Transfer", "⚠️ 忽略传输完成后的次要错误: $error")
        }
    }

    override fun onTransferCancelled() {
        Log.d("Transfer", "传输取消")
        transferStatus = FileTransferManager.TransferStatus()
        transferProgress = 0f
        
        // ✅ 修复：传输取消后完全重置蓝牙状态，确保下次可以重新连接
        isNfcConnected = false
        bluetoothDeviceName = ""
        isPairingCompleted = false
        bluetoothManager.disconnect()
        
        // ✅ 重置配对状态（但不立即启动）
        bluetoothOOBPairingManager.resetPairingState()
        
        Log.d("Transfer", "✅ 传输取消，已重置蓝牙状态")
        
        // ✅ 跳转到 HOME 页面，然后才启动配对
        currentScreen = Screen.HOME
        
        // ✅ 只在跳转到 HOME 后才重新初始化配对
        updateModeConfiguration()
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
        
        // 重要：不要在这里设置 isNfcConnected = true
        // 只有在 Socket 真正连接成功后（onDeviceConnected 回调）才设置
        // 这里只更新设备名称
        bluetoothDeviceName = deviceName
        
        // 建立实际的蓝牙Socket连接（异步操作）
        if (isSenderMode) {
            // 发送端：连接到接收端设备
            bluetoothManager.connectToDevice(device.address)
            Log.d("OOBPairing", "发送端正在异步建立Socket连接到设备: ${device.address}")
        } else {
            // 接收端：设备已经作为服务器运行，等待连接
            // 注意：服务器应该已经在 updateModeConfiguration() 中启动
            Log.d("OOBPairing", "接收端已启动服务器，等待发送端Socket连接")
        }
    }
}

/**
 * 格式化文件大小
 */
fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun NFCBeamApp(
    currentScreen: Screen,
    transferProgress: Float,
    transferStatus: FileTransferManager.TransferStatus,
    transferredFiles: List<String>,
    transferredFileInfos: List<FileTransferManager.FileInfo>,
    totalTransferSize: Long,
    isSenderMode: Boolean,
    isTransferSuccess: Boolean,
    bluetoothDeviceName: String,
    isNfcConnected: Boolean,
    currentDownloadLocation: DownloadPathManager.Companion.DownloadLocation,
    customPathDisplayName: String,
    onCustomFolderPicker: () -> Unit,
    onDownloadLocationChange: (DownloadPathManager.Companion.DownloadLocation) -> Unit,
    onFilesSelected: (List<Uri>) -> Unit,
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
    onFileSelectionChange: (List<Uri>) -> Unit,
    onCancelTransfer: () -> Unit,
    onTransferComplete: (Boolean) -> Unit,
    onToggleMode: () -> Unit,
    onScreenChange: (Screen) -> Unit,
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
                isSenderMode = isSenderMode,
                currentDownloadLocation = currentDownloadLocation,
                onDownloadLocationChange = onDownloadLocationChange,
                customPathDisplayName = customPathDisplayName,
                onCustomFolderPicker = onCustomFolderPicker
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
                transferredFileInfos = transferredFileInfos,
                totalSize = formatFileSize(totalTransferSize),
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
