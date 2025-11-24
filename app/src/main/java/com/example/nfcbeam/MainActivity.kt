package com.example.nfcbeam

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity(), FileTransferManager.TransferListener {
    
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var fileTransferManager: FileTransferManager
    
    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initializeNFC()
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
    
    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFiles = listOf(it)
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    // 多文件选择器
    private val multipleFilesPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedFiles = uris
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFiles = listOf(it)
            currentScreen = Screen.TRANSFER_IN_PROGRESS
        }
    }
    
    // 视频选择器
    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFiles = listOf(it)
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化管理器
        bluetoothManager = BluetoothManager(this)
        fileTransferManager = FileTransferManager(this, bluetoothManager)
        
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
                        isTransferSuccess = isTransferSuccess,
                        bluetoothDeviceName = bluetoothDeviceName,
                        isNfcConnected = isNfcConnected,
                        onScreenChange = { screen -> currentScreen = screen },
                        onFilesSelected = { files -> 
                            selectedFiles = files
                            currentScreen = Screen.TRANSFER_IN_PROGRESS
                        },
                        onTransferStart = { startTransfer() },
                        onBackToHome = { 
                            resetTransferState()
                            currentScreen = Screen.HOME 
                        },
                        onRetryTransfer = { startTransfer() },
                        onRequestPermissions = { requestPermissions() },
                        onPhotoPicker = { 
                            // 打开图片选择器，设置图片MIME类型
                            imagePickerLauncher.launch(arrayOf("image/*"))
                        },
                        onVideoPicker = { 
                            // 打开视频选择器，设置视频MIME类型
                            videoPickerLauncher.launch(arrayOf("video/*"))
                        },
                        onFilePicker = { mime -> 
                            // 打开文件选择器，使用传入的MIME类型
                            filePickerLauncher.launch(arrayOf(mime))
                        },
                        onFolderPicker = { 
                            // 打开文件夹选择器
                            folderPickerLauncher.launch(null)
                        }
                    )
                }
            }
        }
        
        // 请求权限
        requestPermissions()
        
        // 处理NFC Intent
        handleNfcIntent(intent)
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        // NFC权限
        permissions.add(Manifest.permission.NFC)
        
        // 蓝牙权限
        permissions.add(Manifest.permission.BLUETOOTH)
        permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
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
        
        // 检查并请求未授予的权限
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            initializeNFC()
            initializeBluetooth()
        }
    }
    
    private fun initializeNFC() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Log.w("NFC", "设备不支持NFC")
            return
        }
        
        if (!nfcAdapter.isEnabled) {
            Log.w("NFC", "NFC功能未启用")
            val intent = Intent(Settings.ACTION_NFC_SETTINGS)
            startActivity(intent)
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
        }
    }
    
    private fun handleNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            rawMessages?.let { messages ->
                for (message in messages) {
                    if (message is NdefMessage) {
                        processNdefMessage(message)
                    }
                }
            }
        }
    }
    
    private fun processNdefMessage(message: NdefMessage) {
        try {
            for (record in message.records) {
                val payload = record.payload
                val text = String(payload, Charsets.UTF_8)
                Log.d("NFC", "Received NFC data: $text")
                
                // 解析蓝牙信息并连接
                if (text.startsWith("BT:")) {
                    val btInfo = text.substring(3)
                    val parts = btInfo.split(":")
                    if (parts.size == 2) {
                        val deviceName = parts[0]
                        val deviceAddress = parts[1]
                        bluetoothManager.connectToDevice(deviceAddress)
                        // NFC连接建立
                        isNfcConnected = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NFC", "处理NDEF消息失败", e)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }
    
    override fun onResume() {
        super.onResume()
        // 启用前台分发系统以处理NFC Intent
        if (::nfcAdapter.isInitialized && nfcAdapter != null) {
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_MUTABLE
            )
            
            val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED))
            
            val techLists = arrayOf(arrayOf<String>())
            
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
        }
    }
    
    override fun onPause() {
        super.onPause()
        if (::nfcAdapter.isInitialized && nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this)
        }
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
    }
    
    // 发送NFC数据
    fun sendNfcData(deviceInfo: String) {
        if (::nfcAdapter.isInitialized && nfcAdapter != null) {
            val message = NdefMessage(
                arrayOf(
                    NdefRecord.createMime(
                        "application/vnd.com.example.nfcbeam",
                        deviceInfo.toByteArray(Charsets.UTF_8)
                    )
                )
            )
            // 使用前台分发系统来推送NFC消息
            val intent = Intent(this, javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_MUTABLE
            )
            
            val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED))
            val techLists = arrayOf(arrayOf<String>())
            
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
        }
    }
    
    // 获取蓝牙信息用于NFC传输
    fun getBluetoothInfoForNfc(): String {
        // 使用BluetoothManager来获取蓝牙信息，它会处理权限检查
        return bluetoothManager.getBluetoothInfoForNFC()
    }

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
}

@Composable
fun NFCBeamApp(
    currentScreen: Screen,
    transferStatus: FileTransferManager.TransferStatus,
    transferredFiles: List<String>,
    isTransferSuccess: Boolean,
    isNfcConnected: Boolean,
    bluetoothDeviceName: String,
    onScreenChange: (Screen) -> Unit,
    onFilesSelected: (List<Uri>) -> Unit,
    onBackToHome: () -> Unit,
    onRetryTransfer: () -> Unit,
    onPhotoPicker: () -> Unit,
    onVideoPicker: () -> Unit,
    onFilePicker: (String) -> Unit,
    onFolderPicker: () -> Unit,
    transferProgress: Float,
    onRequestPermissions: () -> Unit,
    onTransferStart: () -> Unit
) {
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }

    when (currentScreen) {
        Screen.HOME -> {
            HomeScreen(
                isNfcConnected = isNfcConnected,
                onSendFiles = {
                    // NFC连接建立后自动跳转到文件选择页面
                    onScreenChange(Screen.FILE_SELECT)
                },
                onReceiveFiles = {
                    // 进入接收模式
                    onScreenChange(Screen.TRANSFER_IN_PROGRESS)
                }
            )
        }
        Screen.FILE_SELECT -> {
            FileSelectPage(
                bluetoothDeviceName = bluetoothDeviceName,
                selectedFiles = selectedFiles,
                onPhotoPicker = onPhotoPicker,
                onVideoPicker = onVideoPicker,
                onFilePicker = onFilePicker,
                onFolderPicker = onFolderPicker,
                onSend = { files ->
                    selectedFiles = files          // 1. 内部更新
                    onFilesSelected(files)         // 2. 通知外部（用于传输页）
                    onScreenChange(Screen.TRANSFER_IN_PROGRESS)
                },
                onFileSelectionChange = { files ->
                    selectedFiles = files          // 仅内部刷新 UI
                }
            )
        }
        Screen.TRANSFER_IN_PROGRESS -> {
            TransferInProgressScreen(
                transferStatus = transferStatus,
                onCancel = onBackToHome,
                onTransferComplete = { success ->
                    // 使用局部变量而不是重新赋值val
                    val successState = success
                    val newScreen = Screen.TRANSFER_COMPLETE
                    onScreenChange(newScreen)
                }
            )
        }
        Screen.TRANSFER_COMPLETE -> {
            TransferCompleteScreen(
                isSuccess = isTransferSuccess,
                transferredFiles = transferredFiles,
                totalSize = "计算中...", // 实际应用中应该计算真实大小
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
