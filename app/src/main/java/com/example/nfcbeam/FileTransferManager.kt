package com.example.nfcbeam

import android.bluetooth.BluetoothSocket
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class FileTransferManager(private val context: Context, private val bluetoothManager: BluetoothManager) {
    
    // 下载路径管理器
    private val downloadPathManager = DownloadPathManager(context)
    
    // WakeLock 用于后台保活
    private var wakeLock: PowerManager.WakeLock? = null
    
    companion object {
        private const val TAG = "FileTransferManager"
        private const val BUFFER_SIZE = 8192
        private const val CHUNK_SIZE = 1024 * 1024 // ✅ 新增：1MB 分片大小
        private const val PROTOCOL_VERSION = 2 // ✅ 升级协议版本支持分片
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 100L
        private const val SOCKET_TIMEOUT_MS = 60000 // 增加到60秒
        private const val LARGE_FILE_THRESHOLD = 50 * 1024 * 1024L // 50MB
        
        // 协议命令
        private const val CMD_START_TRANSFER = 0x01
        private const val CMD_FILE_INFO = 0x02
        private const val CMD_FILE_DATA = 0x03
        private const val CMD_TRANSFER_COMPLETE = 0x04
        private const val CMD_TRANSFER_ERROR = 0x05
        private const val CMD_CANCEL_TRANSFER = 0x06
        private const val CMD_ACK = 0x07
        // ✅ 新增：分片传输命令
        private const val CMD_CHUNK_START = 0x08
        private const val CMD_CHUNK_DATA = 0x09
        private const val CMD_CHUNK_END = 0x0A
    }
    
    // ✅ 动态线程池配置
    private val cpuCount = Runtime.getRuntime().availableProcessors()
    private val corePoolSize = min(cpuCount, 4)
    private val maxPoolSize = min(cpuCount * 2, 8)
    
    // ✅ 主传输线程池（单线程，避免蓝牙冲突）
    private val transferExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r ->
            Thread(r, "FileTransfer-Main").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = false
            }
        }
    )
    
    // ✅ 文件处理线程池（多线程，用于预处理）
    private val fileProcessExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        corePoolSize, maxPoolSize, 60L, TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        ThreadFactory { r ->
            Thread(r, "FileProcess-${System.currentTimeMillis()}").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
    )
    
    // ✅ 进度计算线程池
    private val progressExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Progress-Calculator").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    
    // ✅ 线程安全的状态管理
    private val isTransferringAtomic = AtomicBoolean(false)
    private val totalTransferredBytesAtomic = AtomicLong(0L)
    private val currentFileIndexAtomic = AtomicInteger(0)
    
    // 传输任务管理
    private var currentTransferTask: Future<*>? = null
    private val activeTasks = ConcurrentHashMap<String, Future<*>>()
    
    // ✅ 进度同步
    private val progressLock = Any()
    private var progressUpdateTask: ScheduledFuture<*>? = null
    
    data class TransferStatus(
        val isConnecting: Boolean = false,
        val isTransferring: Boolean = false,
        val isCompleted: Boolean = false,
        val isSuccess: Boolean = false,
        val progress: Float = 0f,
        val totalFiles: Int = 0,
        val transferredFiles: Int = 0,
        val currentFileName: String? = null,
        val errorMessage: String? = null
    )
    
    data class FileInfo(
        val fileName: String,
        val fileSize: Long,
        val fileType: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * ✅ 文件分片信息
     */
    data class FileChunk(
        val chunkIndex: Int,
        val totalChunks: Int,
        val chunkSize: Int,
        val offset: Long
    )
    
    // 传输状态监听器
    interface TransferListener {
        fun onTransferStarted(totalFiles: Int, totalSize: Long)
        fun onFileTransferStarted(fileInfo: FileInfo, currentFile: Int, totalFiles: Int)
        fun onTransferProgress(transferredBytes: Long, totalBytes: Long, currentFile: Int, totalFiles: Int)
        fun onTransferCompleted(transferredFiles: List<FileInfo>, totalSize: Long)
        fun onTransferError(error: String)
        fun onTransferCancelled()
    }
    
    private var currentStatus = TransferStatus()
    private var transferListener: TransferListener? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isTransferring = false
    private var currentFileIndex = 0
    private var totalFiles = 0
    private var totalTransferredBytes = 0L
    private var totalFileSize = 0L
    
    private val pendingFiles = mutableListOf<Uri>()
    private val transferredFileInfoList = mutableListOf<FileInfo>()
    
    /**
     * ✅ 启动进度更新任务
     */
    private fun startProgressUpdates() {
        progressUpdateTask?.cancel(false)
        progressUpdateTask = progressExecutor.scheduleAtFixedRate({
            try {
                val transferred = totalTransferredBytesAtomic.get()
                val currentFile = currentFileIndexAtomic.get()
                
                mainHandler.post {
                    transferListener?.onTransferProgress(
                        transferred,
                        totalFileSize,
                        currentFile,
                        totalFiles
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "进度更新失败", e)
            }
        }, 0, 100, TimeUnit.MILLISECONDS)
        Log.d(TAG, "✅ 进度更新任务已启动（每100ms）")
    }
    
    /**
     * ✅ 停止进度更新
     */
    private fun stopProgressUpdates() {
        progressUpdateTask?.cancel(false)
        progressUpdateTask = null
        Log.d(TAG, "✅ 进度更新任务已停止")
    }
    
    /**
     * ✅ 清理线程池资源
     */
    fun shutdown() {
        try {
            Log.d(TAG, "🛑 开始关闭线程池...")
            
            // 取消当前传输任务
            currentTransferTask?.cancel(true)
            
            // 停止进度更新
            stopProgressUpdates()
            
            // 关闭传输线程池
            transferExecutor.shutdown()
            if (!transferExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                transferExecutor.shutdownNow()
                Log.w(TAG, "传输线程池强制关闭")
            }
            
            // 关闭文件处理线程池
            fileProcessExecutor.shutdown()
            if (!fileProcessExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fileProcessExecutor.shutdownNow()
                Log.w(TAG, "文件处理线程池强制关闭")
            }
            
            // 关闭进度线程池
            progressExecutor.shutdown()
            if (!progressExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                progressExecutor.shutdownNow()
            }
            
            Log.d(TAG, "✅ 所有线程池已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭线程池失败", e)
        }
    }
    
    /**
     * 获取 WakeLock 以防止传输过程中设备休眠
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NFCBeam::FileTransferWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L) // 10分钟超时
            }
            Log.d(TAG, "✅ WakeLock 已获取，防止传输中断")
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败", e)
        }
    }
    
    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "✅ WakeLock 已释放")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "释放 WakeLock 失败", e)
        }
    }
    
    /**
     * 请求持久化 URI 权限
     */
    private fun takePersistableUriPermission(uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Log.d(TAG, "✅ 已获取持久化 URI 权限: $uri")
        } catch (e: SecurityException) {
            Log.w(TAG, "无法获取持久化 URI 权限: $uri", e)
        }
    }
    
    /**
     * ✅ 使用独立线程预拷贝大文件到缓存目录
     */
    private fun preCopyLargeFileToCache(uri: Uri, fileSize: Long): Uri {
        if (fileSize < LARGE_FILE_THRESHOLD) {
            return uri // 小文件直接返回原 URI
        }
        
        return try {
            Log.d(TAG, "📦 大文件检测 (${fileSize / 1024 / 1024}MB)，启动预拷贝线程...")
            
            // ✅ 使用Future等待预拷贝完成
            val copyFuture = fileProcessExecutor.submit<Uri> {
                try {
                    val cacheDir = File(context.cacheDir, "large_files")
                    if (!cacheDir.exists()) {
                        cacheDir.mkdirs()
                    }
                    
                    // 获取原文件名
                    val fileName = getFileInfo(uri)?.fileName ?: "temp_${System.currentTimeMillis()}"
                    val cacheFile = File(cacheDir, fileName)
                    
                    Log.d(TAG, "🔄 [预拷贝线程-${Thread.currentThread().name}] 开始拷贝: $fileName")
                    
                    // 拷贝文件到缓存
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            var totalCopied = 0L
                            
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalCopied += bytesRead
                                
                                // 每10MB记录一次进度
                                if (totalCopied % (10 * 1024 * 1024) == 0L) {
                                    Log.d(TAG, "📊 [预拷贝线程] 进度: ${totalCopied / 1024 / 1024}MB / ${fileSize / 1024 / 1024}MB")
                                }
                            }
                        }
                    }
                    
                    Log.d(TAG, "✅ [预拷贝线程] 完成: ${cacheFile.absolutePath}")
                    Uri.fromFile(cacheFile)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [预拷贝线程] 失败", e)
                    uri // 失败时返回原URI
                }
            }
            
            // 等待预拷贝完成（最多等待10分钟）
            copyFuture.get(10, TimeUnit.MINUTES)
            
        } catch (e: Exception) {
            Log.e(TAG, "预拷贝大文件超时或失败，使用原 URI", e)
            uri
        }
    }
    
    fun startFileTransfer(fileUris: List<Uri>) {
        // ✅ 使用原子操作检查和设置传输状态
        if (!isTransferringAtomic.compareAndSet(false, true)) {
            Toast.makeText(context, "传输正在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        
        val socket = bluetoothManager.getClientSocket()
        if (socket == null) {
            Log.e(TAG, "蓝牙Socket为null，无法开始传输")
            isTransferringAtomic.set(false)
            mainHandler.post {
                transferListener?.onTransferError("蓝牙未连接")
                Toast.makeText(context, "蓝牙未连接", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        // 验证Socket连接状态
        try {
            val testInputStream = socket.inputStream
            val testOutputStream = socket.outputStream
            if (testInputStream == null || testOutputStream == null) {
                Log.e(TAG, "无法获取Socket输入输出流")
                isTransferringAtomic.set(false)
                mainHandler.post {
                    transferListener?.onTransferError("蓝牙连接异常")
                    Toast.makeText(context, "蓝牙连接异常", Toast.LENGTH_SHORT).show()
                }
                return
            }
            Log.d(TAG, "✅ Socket连接验证通过")
        } catch (e: Exception) {
            Log.e(TAG, "Socket连接验证失败", e)
            isTransferringAtomic.set(false)
            mainHandler.post {
                transferListener?.onTransferError("蓝牙连接验证失败: ${e.message}")
                Toast.makeText(context, "蓝牙连接验证失败", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        Log.d(TAG, "🚀 开始文件传输: ${fileUris.size} 个文件")
        
        // 请求持久化 URI 权限
        fileUris.forEach { uri ->
            takePersistableUriPermission(uri)
        }
        
        if (fileUris.isEmpty()) {
            Log.e(TAG, "没有可传输的文件")
            isTransferringAtomic.set(false)
            mainHandler.post {
                transferListener?.onTransferError("没有可传输的文件")
                Toast.makeText(context, "没有可传输的文件", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        pendingFiles.clear()
        pendingFiles.addAll(fileUris)
        totalFiles = fileUris.size
        
        // 计算总文件大小
        totalFileSize = calculateTotalFileSize(fileUris)

        // 重置传输状态
        totalTransferredBytesAtomic.set(0L)
        currentFileIndexAtomic.set(0)
        isTransferring = true
        transferredFileInfoList.clear()
        
        // 获取 WakeLock
        acquireWakeLock()
        
        // ✅ 启动进度更新
        startProgressUpdates()
        
        Log.d(TAG, "📊 传输统计: 文件数=$totalFiles, 总大小=${totalFileSize / 1024 / 1024}MB")
        Log.d(TAG, "   CPU核心数: $cpuCount, 线程池: $corePoolSize-$maxPoolSize")
        
        // 通知监听器传输开始
        mainHandler.post {
            transferListener?.onTransferStarted(totalFiles, totalFileSize)
        }
        
        // ✅ 使用专用传输线程池执行传输任务
        currentTransferTask = transferExecutor.submit {
            try {
                Log.d(TAG, "📤 [传输线程-${Thread.currentThread().name}] 开始")
                performFileTransfer(socket)
            } catch (e: Exception) {
                Log.e(TAG, "❌ [传输线程] 异常", e)
            } finally {
                isTransferringAtomic.set(false)
                stopProgressUpdates()
                Log.d(TAG, "🏁 [传输线程] 结束")
            }
        }
    }
    
    /**
     * 检查 URI 是否是文件夹
     * ✅ 修复：增强目录检测逻辑
     */
    private fun isDirectory(uri: Uri): Boolean {
        return try {
            // 1. 优先使用官方 Tree URI API
            if (DocumentsContract.isTreeUri(uri)) {
                Log.d(TAG, "✅ 检测到 Tree URI（文件夹）: $uri")
                return true
            }

            // 2. 检查 MIME 类型
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                Log.d(TAG, "✅ 检测到文件夹 MIME 类型: $uri")
                return true
            }

            // 3. 尝试通过 query 检查是否为目录
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (mimeCol != -1) {
                        val queriedMime = cursor.getString(mimeCol)
                        if (queriedMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            Log.d(TAG, "✅ 通过 query 检测到文件夹: $uri")
                            return true
                        }
                    }
                }
            }

            // 4. 尝试打开输入流，如果失败可能是目录
            try {
                context.contentResolver.openInputStream(uri)?.close()
                Log.d(TAG, "✅ 检测到文件（可打开输入流）: $uri")
                false
            } catch (e: Exception) {
                // 无法打开输入流，可能是目录
                Log.d(TAG, "⚠️ 无法打开输入流，可能是目录: $uri")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查是否为文件夹失败: ${e.message}", e)
            false
        }
    }

    fun listFilesInDirectory(treeUri: Uri): List<Uri> {
        return expandDirectory(treeUri) // 复用现有逻辑
    }
    /**
     * 递归展开文件夹，返回所有叶子文件的 URI 列表
     * ✅ 修复：使用栈实现非递归遍历，避免栈溢出
     */
    private fun expandDirectory(directoryUri: Uri): List<Uri> {
        val files = mutableListOf<Uri>()
        val stack = Stack<Uri>()
        stack.push(directoryUri)
        
        Log.d(TAG, "🔍 开始展开文件夹: $directoryUri")

        while (stack.isNotEmpty()) {
            val currentUri = stack.pop()
            try {
                // 获取文档 ID
                val documentId = if (DocumentsContract.isTreeUri(currentUri)) {
                    DocumentsContract.getTreeDocumentId(currentUri)
                } else {
                    DocumentsContract.getDocumentId(currentUri)
                }
                
                // 构建子文档查询 URI
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    currentUri,
                    documentId
                )

                Log.d(TAG, "📂 查询子项: documentId=$documentId")

                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                    Log.d(TAG, "📊 找到 ${cursor.count} 个子项")

                    while (cursor.moveToNext()) {
                        try {
                            val docId = cursor.getString(idCol)
                            val mimeType = cursor.getString(mimeCol)
                            val displayName = cursor.getString(nameCol)
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(currentUri, docId)

                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                // 是目录，入栈继续遍历
                                Log.d(TAG, "📁 发现子文件夹: $displayName")
                                stack.push(childUri)
                            } else {
                                // 是文件，添加到结果列表
                                Log.d(TAG, "📄 发现文件: $displayName (MIME: $mimeType)")
                                files.add(childUri)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ 跳过无法处理的子项: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ 跳过无法访问的目录: $currentUri, 错误: ${e.message}")
            }
        }
        
        Log.d(TAG, "✅ 文件夹展开完成，共找到 ${files.size} 个文件")
        return files
    }
    
    private fun performFileTransfer(socket: BluetoothSocket) {
        var hasErrors = false
        val failedFiles = mutableListOf<String>()
        
        try {
            Log.d(TAG, "📡 [传输线程-${Thread.currentThread().name}] 开始执行传输")
            
            // 设置 Socket 超时
            try {
                socket.inputStream.available() // 测试连接
                Log.d(TAG, "✅ Socket 连接正常")
            } catch (e: Exception) {
                Log.e(TAG, "Socket 连接测试失败", e)
                throw IOException("Socket 连接异常")
            }
            
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            
            if (inputStream == null || outputStream == null) {
                throw IOException("无法获取Socket输入输出流")
            }
            
            setStatus(TransferStatus(isTransferring = true))
            
            Log.d(TAG, "Socket连接成功，开始发送文件")
            
            // 发送开始传输命令
            sendStartTransferCommand()
            
            // 逐个传输文件
            for ((index, fileUri) in pendingFiles.withIndex()) {
                // ✅ 检查线程中断状态
                if (Thread.currentThread().isInterrupted || !isTransferringAtomic.get()) {
                    Log.d(TAG, "⚠️ [传输线程] 检测到中断信号，停止传输")
                    break
                }
                
                currentFileIndexAtomic.set(index + 1)
                
                Log.d(TAG, "📤 [传输线程] 处理文件 ${index + 1}/$totalFiles")
                
                try {
                    val fileInfo = getFileInfo(fileUri)
                    if (fileInfo != null) {
                        // ✅ 大文件使用分片传输
                        if (fileInfo.fileSize >= LARGE_FILE_THRESHOLD) {
                            Log.d(TAG, "📦 大文件检测，使用分片传输")
                            transferFileWithChunks(fileUri, fileInfo, index)
                        } else {
                            transferSingleFile(fileUri, index)
                        }
                    } else {
                        throw IOException("无法获取文件信息")
                    }
                } catch (e: Exception) {
                    hasErrors = true
                    val fileName = fileUri.lastPathSegment ?: fileUri.toString()
                    failedFiles.add(fileName)
                    Log.e(TAG, "❌ 文件传输失败: $fileName", e)
                }
            }
            
            if (isTransferringAtomic.get()) {
                // 发送传输完成命令
                sendTransferCompleteCommand()
                
                // 根据是否有错误设置最终状态
                if (hasErrors) {
                    val errorMsg = "部分文件传输失败 (${failedFiles.size}/${pendingFiles.size}): ${failedFiles.joinToString(", ")}"
                    setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = errorMsg))
                    
                    mainHandler.post {
                        transferListener?.onTransferError(errorMsg)
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    }
                } else {
                    setStatus(TransferStatus(isCompleted = true, isSuccess = true))
                    
                    mainHandler.post {
                        transferListener?.onTransferCompleted(transferredFileInfoList.toList(), totalFileSize)
                        Toast.makeText(context, "文件传输完成", Toast.LENGTH_LONG).show()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ [传输线程] 传输失败", e)
            setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message))
            
            try {
                sendTransferErrorCommand(e.message ?: "未知错误")
            } catch (sendError: Exception) {
                Log.e(TAG, "发送错误命令失败", sendError)
            }
            
            mainHandler.post {
                transferListener?.onTransferError(e.message ?: "传输失败")
                Toast.makeText(context, "文件传输失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            isTransferring = false
            isTransferringAtomic.set(false)
            releaseWakeLock()
            Log.d(TAG, "🏁 [传输线程] 传输流程结束")
        }
    }
    
    private fun transferSingleFile(fileUri: Uri, fileIndex: Int) {
        val fileInfo = getFileInfo(fileUri) ?: throw IOException("无法获取文件信息")
        
        Log.d(TAG, "📤 传输文件 ${fileIndex + 1}/$totalFiles: ${fileInfo.fileName}")
            
            if (fileInfo.fileSize == 0L) {
                Log.e(TAG, "❌ 文件大小为0，无法传输: ${fileInfo.fileName}")
                throw IOException("文件大小为0: ${fileInfo.fileName}")
            }
            
            setStatus(TransferStatus(isTransferring = true, currentFileName = fileInfo.fileName))
            
            // 发送文件信息
            Log.d(TAG, "📋 发送文件信息...")
            sendFileInfo(fileInfo)
            
            // 等待接收方确认文件信息
            Log.d(TAG, "⏳ 等待接收方ACK...")
            waitForAck()
            
            // 通知监听器文件传输开始
            mainHandler.post {
                transferListener?.onFileTransferStarted(fileInfo, fileIndex + 1, totalFiles)
            }
            
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw IOException("无法打开文件流")
        
        inputStream.use { fileStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            var totalBytesRead = 0L
            
            while (fileStream.read(buffer).also { bytesRead = it } != -1 && isTransferringAtomic.get()) {
                // ✅ 检查线程中断
                if (Thread.currentThread().isInterrupted) {
                    Log.w(TAG, "⚠️ [传输线程] 检测到中断，停止文件传输")
                    break
                }
                
                if (bytesRead > 0) {
                    sendFileData(buffer, bytesRead)
                    totalBytesRead += bytesRead
                    // ✅ 使用原子操作更新进度
                    totalTransferredBytesAtomic.addAndGet(bytesRead.toLong())
                    
                    Thread.sleep(1) // 防止过快
                }
            }
            
            Log.d(TAG, "✅ 文件传输完成: ${fileInfo.fileName}")
            transferredFileInfoList.add(fileInfo)
        }
    }
    
    /**
     * ✅ 使用分片传输大文件
     */
    private fun transferFileWithChunks(fileUri: Uri, fileInfo: FileInfo, fileIndex: Int) {
        Log.d(TAG, "📦 大文件分片传输: ${fileInfo.fileName} (${fileInfo.fileSize / 1024 / 1024}MB)")
        
        setStatus(TransferStatus(isTransferring = true, currentFileName = fileInfo.fileName))
        
        sendFileInfo(fileInfo)
        waitForAck()
        
        mainHandler.post {
            transferListener?.onFileTransferStarted(fileInfo, fileIndex + 1, totalFiles)
        }
        
        val totalChunks = ((fileInfo.fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        Log.d(TAG, "📊 分片数量: $totalChunks")
        
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw IOException("无法打开文件流")
        
        inputStream.use { fileStream ->
            for (chunkIndex in 0 until totalChunks) {
                if (Thread.currentThread().isInterrupted || !isTransferringAtomic.get()) {
                    break
                }
                
                val offset = chunkIndex.toLong() * CHUNK_SIZE
                val remainingBytes = fileInfo.fileSize - offset
                val currentChunkSize = min(CHUNK_SIZE.toLong(), remainingBytes).toInt()
                
                val chunk = FileChunk(chunkIndex, totalChunks, currentChunkSize, offset)
                
                Log.d(TAG, "📦 发送分片 ${chunkIndex + 1}/$totalChunks")
                sendChunkStart(chunk)
                
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesReadInChunk = 0
                
                while (bytesReadInChunk < currentChunkSize) {
                    val bytesToRead = min(BUFFER_SIZE, currentChunkSize - bytesReadInChunk)
                    val bytesRead = fileStream.read(buffer, 0, bytesToRead)
                    
                    if (bytesRead == -1) break
                    
                    sendChunkData(buffer, bytesRead)
                    bytesReadInChunk += bytesRead
                    totalTransferredBytesAtomic.addAndGet(bytesRead.toLong())
                    
                    Thread.sleep(1)
                }
                
                sendChunkEnd(chunk)
                waitForAck()
                
                Log.d(TAG, "✅ 分片 ${chunkIndex + 1}/$totalChunks 完成")
            }
        }
        
        Log.d(TAG, "✅ 大文件传输完成: ${fileInfo.fileName}")
        transferredFileInfoList.add(fileInfo)
    }
    
    /**
     * ✅ 发送分片开始命令
     */
    private fun sendChunkStart(chunk: FileChunk) {
        outputStream?.let { os ->
            val data = ByteArray(13)
            data[0] = CMD_CHUNK_START.toByte()
            // chunkIndex (4 bytes)
            data[1] = (chunk.chunkIndex shr 24).toByte()
            data[2] = (chunk.chunkIndex shr 16).toByte()
            data[3] = (chunk.chunkIndex shr 8).toByte()
            data[4] = chunk.chunkIndex.toByte()
            // totalChunks (4 bytes)
            data[5] = (chunk.totalChunks shr 24).toByte()
            data[6] = (chunk.totalChunks shr 16).toByte()
            data[7] = (chunk.totalChunks shr 8).toByte()
            data[8] = chunk.totalChunks.toByte()
            // chunkSize (4 bytes)
            data[9] = (chunk.chunkSize shr 24).toByte()
            data[10] = (chunk.chunkSize shr 16).toByte()
            data[11] = (chunk.chunkSize shr 8).toByte()
            data[12] = chunk.chunkSize.toByte()
            
            os.write(data)
            os.flush()
        }
    }
    
    /**
     * ✅ 发送分片数据
     */
    private fun sendChunkData(buffer: ByteArray, length: Int) {
        outputStream?.let { os ->
            val header = ByteArray(5)
            header[0] = CMD_CHUNK_DATA.toByte()
            header[1] = (length shr 24).toByte()
            header[2] = (length shr 16).toByte()
            header[3] = (length shr 8).toByte()
            header[4] = length.toByte()
            
            os.write(header)
            os.write(buffer, 0, length)
            os.flush()
        }
    }
    
    /**
     * ✅ 发送分片结束命令
     */
    private fun sendChunkEnd(chunk: FileChunk) {
        outputStream?.let { os ->
            val data = ByteArray(5)
            data[0] = CMD_CHUNK_END.toByte()
            data[1] = (chunk.chunkIndex shr 24).toByte()
            data[2] = (chunk.chunkIndex shr 16).toByte()
            data[3] = (chunk.chunkIndex shr 8).toByte()
            data[4] = chunk.chunkIndex.toByte()
            
            os.write(data)
            os.flush()
        }
    }
    
    private fun getFileInfo(fileUri: Uri): FileInfo? {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            
            // ✅ 修复：先检查是否为目录
            val mimeType = contentResolver.getType(fileUri)
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                Log.w(TAG, "⚠️ getFileInfo 检测到目录，返回 null: $fileUri")
                return null
            }
            
            // 获取文件名和大小
            var fileName = "unknown"
            var fileSize = 0L
            
            contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // 使用OpenableColumns常量获取文件名
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex) ?: "unknown"
                    }
                    
                    // 使用OpenableColumns常量获取文件大小
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
            
            // 使用之前获取的 MIME 类型，如果为空则使用默认值
            val finalMimeType = mimeType ?: "application/octet-stream"
            
            Log.d(TAG, "获取文件信息: 文件名=$fileName, 大小=$fileSize, 类型=$finalMimeType")
            
            // 如果文件大小为0，尝试通过输入流获取
            if (fileSize == 0L) {
                try {
                    contentResolver.openInputStream(fileUri)?.use { inputStream ->
                        fileSize = inputStream.available().toLong()
                        Log.d(TAG, "通过输入流获取文件大小: $fileSize")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法通过输入流获取文件大小: ${e.message}")
                    // 如果无法打开输入流，可能是目录
                    return null
                }
            }
            
            return FileInfo(
                fileName = fileName,
                fileSize = fileSize,
                fileType = finalMimeType
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取文件信息失败: ${fileUri}, 错误: ${e.message}", e)
            null
        }
    }
    
    private fun calculateTotalFileSize(fileUris: List<Uri>): Long {
        var totalSize = 0L
        for (uri in fileUris) {
            val fileInfo = getFileInfo(uri)
            fileInfo?.let {
                totalSize += it.fileSize
            }
        }
        return totalSize
    }
    
    private fun sendStartTransferCommand() {
        outputStream?.let { os ->
            val data = ByteArray(5)
            data[0] = CMD_START_TRANSFER.toByte()
            data[1] = PROTOCOL_VERSION.toByte()
            data[2] = (totalFiles shr 16).toByte()
            data[3] = (totalFiles shr 8).toByte()
            data[4] = totalFiles.toByte()
            os.write(data)
            os.flush()
        }
    }
    
    private fun sendFileInfo(fileInfo: FileInfo) {
        outputStream?.let { os ->
            val fileNameBytes = fileInfo.fileName.toByteArray(Charsets.UTF_8)
            val fileTypeBytes = fileInfo.fileType.toByteArray(Charsets.UTF_8)
            
            // 确保文件名和文件类型长度在有效范围内
            val fileNameLength = minOf(fileNameBytes.size, 65535)
            val fileTypeLength = minOf(fileTypeBytes.size, 65535)
            
            val header = ByteArray(13 + fileNameLength + fileTypeLength)
            header[0] = CMD_FILE_INFO.toByte()
            
            // 文件大小 (8 bytes)
            for (i in 0 until 8) {
                header[1 + i] = (fileInfo.fileSize shr (56 - i * 8)).toByte()
            }
            
            // 文件名长度 (2 bytes)
            header[9] = (fileNameLength shr 8).toByte()
            header[10] = fileNameLength.toByte()
            
            // 文件类型长度 (2 bytes)
            header[11] = (fileTypeLength shr 8).toByte()
            header[12] = fileTypeLength.toByte()
            
            // 文件名和文件类型
            System.arraycopy(fileNameBytes, 0, header, 13, fileNameLength)
            System.arraycopy(fileTypeBytes, 0, header, 13 + fileNameLength, fileTypeLength)
            
            os.write(header)
            os.flush()
            Log.d(TAG, "发送文件信息: ${fileInfo.fileName}, 大小: ${fileInfo.fileSize}")
        }
    }
    
    private fun sendFileData(buffer: ByteArray, length: Int) {
        outputStream?.let { os ->
            val header = ByteArray(5)
            header[0] = CMD_FILE_DATA.toByte()
            header[1] = (length shr 24).toByte()
            header[2] = (length shr 16).toByte()
            header[3] = (length shr 8).toByte()
            header[4] = length.toByte()
            
            try {
                // 直接发送，不使用重试机制（重试可能导致数据重复）
                os.write(header)
                os.write(buffer, 0, length)
                os.flush()
            } catch (e: IOException) {
                Log.e(TAG, "❌ 发送文件数据失败: ${e.message}")
                throw e
            }
        } ?: throw IOException("输出流为null")
    }
    
    private fun sendWithRetry(os: OutputStream, header: ByteArray, data: ByteArray, length: Int) {
        var retryCount = 0
        while (retryCount < MAX_RETRY_COUNT) {
            try {
                os.write(header)
                os.write(data, 0, length)
                os.flush()
                return // 发送成功，退出重试循环
            } catch (e: IOException) {
                retryCount++
                Log.w(TAG, "发送数据失败，重试 $retryCount/$MAX_RETRY_COUNT: ${e.message}")
                if (retryCount >= MAX_RETRY_COUNT) {
                    throw e // 达到最大重试次数，抛出异常
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
    }
    
    /**
     * 完整读取指定字节数的数据，确保读取完整
     * @param inputStream 输入流
     * @param buffer 缓冲区
     * @param offset 偏移量
     * @param length 需要读取的字节数
     * @return 实际读取的字节数，如果连接断开返回-1
     */
    private fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var totalRead = 0
        var retryCount = 0
        
        while (totalRead < length && retryCount < MAX_RETRY_COUNT) {
            try {
                val remaining = length - totalRead
                val bytesRead = inputStream.read(buffer, offset + totalRead, remaining)
                
                if (bytesRead == -1) {
                    // 连接已断开
                    Log.e(TAG, "连接已断开，已读取 $totalRead/$length 字节")
                    return -1
                } else if (bytesRead == 0) {
                    // 没有数据可读，稍等后重试
                    retryCount++
                    Log.w(TAG, "读取返回0字节，重试 $retryCount/$MAX_RETRY_COUNT (已读取 $totalRead/$length)")
                    Thread.sleep(RETRY_DELAY_MS)
                } else {
                    // 成功读取数据
                    totalRead += bytesRead
                    retryCount = 0 // 重置重试计数
                    
                    if (totalRead < length) {
                        Log.d(TAG, "部分读取: $totalRead/$length 字节，继续读取...")
                    }
                }
            } catch (e: IOException) {
                retryCount++
                Log.w(TAG, "读取数据异常，重试 $retryCount/$MAX_RETRY_COUNT: ${e.message}")
                if (retryCount >= MAX_RETRY_COUNT) {
                    throw e
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        
        if (totalRead < length) {
            Log.w(TAG, "⚠️ 读取不完整: 期望 $length 字节，实际读取 $totalRead 字节")
        }
        
        return totalRead
    }
    
    private fun waitForAck() {
        try {
            inputStream?.let { input ->
                val ackByte = input.read()
                if (ackByte == CMD_ACK) {
                    Log.d(TAG, "收到ACK确认")
                } else {
                    Log.w(TAG, "收到非ACK响应: $ackByte")
                    // 继续传输，不抛出异常
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "等待ACK超时或失败: ${e.message}")
            // 继续传输，不抛出异常
        }
    }
    
    private fun sendAck() {
        outputStream?.let { os ->
            val data = ByteArray(1)
            data[0] = CMD_ACK.toByte()
            os.write(data)
            os.flush()
            Log.d(TAG, "发送ACK确认")
        }
    }
    
    private fun sendTransferCompleteCommand() {
        outputStream?.let { os ->
            val data = ByteArray(1)
            data[0] = CMD_TRANSFER_COMPLETE.toByte()
            os.write(data)
            os.flush()
        }
    }
    
    private fun sendTransferErrorCommand(error: String) {
        outputStream?.let { os ->
            val errorBytes = error.toByteArray(Charsets.UTF_8)
            val data = ByteArray(3 + errorBytes.size)
            data[0] = CMD_TRANSFER_ERROR.toByte()
            data[1] = (errorBytes.size shr 8).toByte()
            data[2] = errorBytes.size.toByte()
            System.arraycopy(errorBytes, 0, data, 3, errorBytes.size)
            os.write(data)
            os.flush()
        }
    }
    
    fun startFileReceiver() {
        if (isTransferring) {
            Log.d(TAG, "文件接收已在进行中")
            return
        }
        
        isTransferring = true
        
        // 获取 WakeLock
        acquireWakeLock()
        
        Log.d(TAG, "启动文件接收器")
        
        executor.execute {
            try {
                val socket = bluetoothManager.getClientSocket()
                if (socket != null && socket.isConnected) {
                    Log.d(TAG, "Socket已连接，开始接收文件")
                    receiveFiles(socket)
                } else {
                    Log.e(TAG, "蓝牙未连接，无法开始接收文件")
                    setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = "蓝牙未连接"))
                    mainHandler.post {
                        transferListener?.onTransferError("蓝牙未连接")
                        Toast.makeText(context, "蓝牙未连接", Toast.LENGTH_SHORT).show()
                    }
                    isTransferring = false
                    releaseWakeLock()
                }
            } catch (e: Exception) {
                Log.e(TAG, "文件接收失败", e)
                setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message ?: "接收失败"))
                mainHandler.post {
                    transferListener?.onTransferError(e.message ?: "接收失败")
                    Toast.makeText(context, "文件接收失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isTransferring = false
                releaseWakeLock()
            }
        }
    }
    
    private fun receiveFiles(socket: BluetoothSocket) {
        try {
            // 设置 Socket 超时
            try {
                socket.inputStream.available() // 测试连接
                Log.d(TAG, "✅ Socket 连接正常")
            } catch (e: Exception) {
                Log.e(TAG, "Socket 连接测试失败", e)
                throw IOException("Socket 连接异常")
            }
            
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            
            if (inputStream == null || outputStream == null) {
                throw IOException("无法获取Socket输入输出流")
            }
            
            setStatus(TransferStatus(isTransferring = true))
            Log.d(TAG, "开始接收文件数据")
            
            var totalFiles = 0
            var currentFileIndex = 0
            var currentFileName: String? = null
            var currentFileSize: Long = 0
            var currentFileType: String? = null
            val receivedFiles = mutableListOf<String>()
            val receivedFileInfoList = mutableListOf<FileInfo>()
            var totalReceivedSize = 0L
            
            // 用于接收文件数据的状态
            var receivingFileData = false
            var fileOutputStream: FileOutputStream? = null
            var totalBytesReceived = 0L
            
            while (isTransferring) {
                val commandByte = inputStream?.read()
                if (commandByte == null || commandByte == -1) {
                    Log.e(TAG, "读取命令失败，连接可能已断开")
                    break
                }
                val command = commandByte
                
                Log.d(TAG, "收到命令: $command")
                
                when (command) {
                    CMD_START_TRANSFER -> {
                        // 读取协议版本和文件总数
                        val version = inputStream?.read() ?: 0
                        val fileCountBytes = ByteArray(3)
                        inputStream?.read(fileCountBytes)
                        totalFiles = (fileCountBytes[0].toInt() and 0xFF shl 16) or
                                   (fileCountBytes[1].toInt() and 0xFF shl 8) or
                                   (fileCountBytes[2].toInt() and 0xFF)
                        
                        Log.d(TAG, "开始接收传输: 版本=$version, 文件数=$totalFiles")
                        mainHandler.post {
                            transferListener?.onTransferStarted(totalFiles, 0L)
                        }
                    }
                    
                    CMD_FILE_INFO -> {
                        // 如果之前有打开的文件流，先关闭
                        fileOutputStream?.close()
                        fileOutputStream = null
                        
                        // 读取文件信息 - 使用readFully确保读取完整的数据
                        val fileSizeBytes = ByteArray(8)
                        val fileSizeRead = readFully(inputStream!!, fileSizeBytes, 0, 8)
                        if (fileSizeRead != 8) {
                            Log.e(TAG, "❌ 读取文件大小失败，期望8字节，实际读取 $fileSizeRead 字节")
                            throw IOException("读取文件大小失败")
                        }
                        currentFileSize = (fileSizeBytes[0].toLong() and 0xFF shl 56) or
                                     (fileSizeBytes[1].toLong() and 0xFF shl 48) or
                                     (fileSizeBytes[2].toLong() and 0xFF shl 40) or
                                     (fileSizeBytes[3].toLong() and 0xFF shl 32) or
                                     (fileSizeBytes[4].toLong() and 0xFF shl 24) or
                                     (fileSizeBytes[5].toLong() and 0xFF shl 16) or
                                     (fileSizeBytes[6].toLong() and 0xFF shl 8) or
                                     (fileSizeBytes[7].toLong() and 0xFF)
                        
                        val fileNameLengthBytes = ByteArray(2)
                        val fileNameLengthRead = readFully(inputStream!!, fileNameLengthBytes, 0, 2)
                        if (fileNameLengthRead != 2) {
                            Log.e(TAG, "❌ 读取文件名长度失败，期望2字节，实际读取 $fileNameLengthRead 字节")
                            throw IOException("读取文件名长度失败")
                        }
                        val fileNameLength = (fileNameLengthBytes[0].toInt() and 0xFF shl 8) or
                                           (fileNameLengthBytes[1].toInt() and 0xFF)
                        
                        val fileTypeLengthBytes = ByteArray(2)
                        val fileTypeLengthRead = readFully(inputStream!!, fileTypeLengthBytes, 0, 2)
                        if (fileTypeLengthRead != 2) {
                            Log.e(TAG, "❌ 读取文件类型长度失败，期望2字节，实际读取 $fileTypeLengthRead 字节")
                            throw IOException("读取文件类型长度失败")
                        }
                        val fileTypeLength = (fileTypeLengthBytes[0].toInt() and 0xFF shl 8) or
                                           (fileTypeLengthBytes[1].toInt() and 0xFF)
                        
                        // 读取文件名
                        val fileNameBytes = ByteArray(fileNameLength)
                        val fileNameBytesRead = readFully(inputStream!!, fileNameBytes, 0, fileNameLength)
                        if (fileNameBytesRead != fileNameLength) {
                            Log.e(TAG, "❌ 读取文件名失败，期望 $fileNameLength 字节，实际读取 $fileNameBytesRead 字节")
                            throw IOException("读取文件名失败")
                        }
                        currentFileName = String(fileNameBytes, Charsets.UTF_8)
                        
                        // 读取文件类型
                        val fileTypeBytes = ByteArray(fileTypeLength)
                        val fileTypeBytesRead = readFully(inputStream!!, fileTypeBytes, 0, fileTypeLength)
                        if (fileTypeBytesRead != fileTypeLength) {
                            Log.e(TAG, "❌ 读取文件类型失败，期望 $fileTypeLength 字节，实际读取 $fileTypeBytesRead 字节")
                            throw IOException("读取文件类型失败")
                        }
                        currentFileType = String(fileTypeBytes, Charsets.UTF_8)
                        
                        currentFileIndex++
                        Log.d(TAG, "接收文件信息: $currentFileName, 大小: $currentFileSize, 类型: $currentFileType, 当前文件: $currentFileIndex/$totalFiles")
                        
                        val fileInfo = FileInfo(currentFileName!!, currentFileSize, currentFileType!!)
                        mainHandler.post {
                            transferListener?.onFileTransferStarted(fileInfo, currentFileIndex, totalFiles)
                        }
                        
                        // 准备接收文件数据 - 使用MediaStore API或直接文件系统
                        val file = saveFileUsingMediaStoreOrFileSystem(currentFileName!!, currentFileType!!)
                        fileOutputStream = FileOutputStream(file)
                        totalBytesReceived = 0L
                        receivingFileData = true
                        
                        Log.d(TAG, "准备接收文件数据到: ${file.absolutePath}")
                        
                        // 发送ACK确认文件信息已接收
                        sendAck()
                    }
                    
                    CMD_FILE_DATA -> {
                        if (!receivingFileData || fileOutputStream == null || currentFileName == null) {
                            Log.e(TAG, "❌ 收到文件数据命令但未准备好接收 (receivingFileData=$receivingFileData, fileOutputStream=$fileOutputStream, currentFileName=$currentFileName)")
                            continue
                        }
                        
                        // 读取数据长度 - 使用readFully确保读取完整
                        val lengthBytes = ByteArray(4)
                        val lengthRead = readFully(inputStream!!, lengthBytes, 0, 4)
                        if (lengthRead != 4) {
                            Log.e(TAG, "❌ 读取数据长度失败，期望4字节，实际读取 $lengthRead 字节")
                            throw IOException("读取数据长度失败")
                        }
                        
                        val dataLength = (lengthBytes[0].toInt() and 0xFF shl 24) or
                                       (lengthBytes[1].toInt() and 0xFF shl 16) or
                                       (lengthBytes[2].toInt() and 0xFF shl 8) or
                                       (lengthBytes[3].toInt() and 0xFF)
                        
                        if (dataLength <= 0 || dataLength > BUFFER_SIZE * 2) {
                            Log.e(TAG, "❌ 数据长度异常: $dataLength")
                            break
                        }
                        
                        // 读取数据 - 使用readFully确保完整读取
                        val buffer = ByteArray(dataLength)
                        val bytesRead = readFully(inputStream!!, buffer, 0, dataLength)
                        
                        if (bytesRead != dataLength) {
                            Log.e(TAG, "❌ 数据块接收不完整: 期望 $dataLength 字节，实际接收 $bytesRead 字节")
                            throw IOException("数据块接收不完整")
                        }
                        
                        // 写入文件
                        fileOutputStream?.write(buffer, 0, bytesRead)
                        totalBytesReceived += bytesRead
                        
                        // 更新进度
                        mainHandler.post {
                            transferListener?.onTransferProgress(
                                totalBytesReceived,
                                currentFileSize,
                                currentFileIndex,
                                totalFiles
                            )
                        }
                        
                        // 每接收一定数量的数据记录日志
                        if (totalBytesReceived % (BUFFER_SIZE * 10) < BUFFER_SIZE) {
                            Log.d(TAG, "📥 已接收: $totalBytesReceived/$currentFileSize 字节 (${(totalBytesReceived * 100 / currentFileSize)}%)")
                        }
                        
                        // 检查文件是否接收完成
                        if (totalBytesReceived >= currentFileSize) {
                            fileOutputStream?.flush()
                            fileOutputStream?.close()
                            fileOutputStream = null
                            receivingFileData = false
                            receivedFiles.add(currentFileName!!)
                            
                            // 记录接收到的文件信息
                            val fileInfo = FileInfo(currentFileName!!, currentFileSize, currentFileType ?: "application/octet-stream")
                            receivedFileInfoList.add(fileInfo)
                            totalReceivedSize += currentFileSize
                            
                            val receivedFile = File(getReceivedFilesDirectory(), currentFileName!!)
                            Log.d(TAG, "✅ 文件接收完成: $currentFileName")
                            Log.d(TAG, "   - 预期大小: $currentFileSize 字节")
                            Log.d(TAG, "   - 实际接收: $totalBytesReceived 字节")
                            Log.d(TAG, "   - 文件路径: ${receivedFile.absolutePath}")
                            Log.d(TAG, "   - 文件存在: ${receivedFile.exists()}")
                            Log.d(TAG, "   - 文件大小: ${receivedFile.length()} 字节")
                            
                            // 发送ACK确认文件数据已完全接收
                            sendAck()
                        }
                    }
                    
                    CMD_TRANSFER_COMPLETE -> {
                        Log.d(TAG, "收到传输完成命令，已接收 ${receivedFiles.size} 个文件")
                        
                        // 关闭可能还打开的文件流
                        fileOutputStream?.close()
                        fileOutputStream = null
                        
                        setStatus(TransferStatus(isCompleted = true, isSuccess = true))
                        mainHandler.post {
                            transferListener?.onTransferCompleted(receivedFileInfoList.toList(), totalReceivedSize)
                            Toast.makeText(context, "文件接收完成，共 ${receivedFiles.size} 个文件", Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                    
                    CMD_TRANSFER_ERROR -> {
                        val errorLengthBytes = ByteArray(2)
                        inputStream?.read(errorLengthBytes)
                        val errorLength = (errorLengthBytes[0].toInt() and 0xFF shl 8) or
                                        (errorLengthBytes[1].toInt() and 0xFF)
                        
                        val errorBytes = ByteArray(errorLength)
                        inputStream?.read(errorBytes)
                        val error = String(errorBytes, Charsets.UTF_8)
                        
                        Log.e(TAG, "传输错误: $error")
                        
                        // 关闭文件流
                        fileOutputStream?.close()
                        fileOutputStream = null
                        
                        setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = error))
                        mainHandler.post {
                            transferListener?.onTransferError(error)
                            Toast.makeText(context, "文件接收失败: $error", Toast.LENGTH_LONG).show()
                        }
                        break
                    }
                    
                    CMD_CANCEL_TRANSFER -> {
                        Log.d(TAG, "传输被取消")
                        
                        // 关闭文件流
                        fileOutputStream?.close()
                        fileOutputStream = null
                        
                        setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = "传输被取消"))
                        mainHandler.post {
                            transferListener?.onTransferCancelled()
                            Toast.makeText(context, "传输被取消", Toast.LENGTH_SHORT).show()
                        }
                        break
                    }
                    
                    else -> {
                        Log.w(TAG, "未知命令: $command")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "接收文件失败", e)
            setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message ?: "接收失败"))
            mainHandler.post {
                transferListener?.onTransferError(e.message ?: "接收失败")
                Toast.makeText(context, "接收文件失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            isTransferring = false
            releaseWakeLock() // 释放 WakeLock
            // 注意：这里不关闭流，保持Socket连接可用
            // 只有在用户明确退出或需要断开连接时才关闭
        }
    }
    
    
    fun cancelTransfer() {
        isTransferring = false
        setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = "传输已取消"))
        
        // 发送取消命令
        outputStream?.let { os ->
            val data = ByteArray(1)
            data[0] = CMD_CANCEL_TRANSFER.toByte()
            os.write(data)
            os.flush()
        }
        
        mainHandler.post {
            transferListener?.onTransferCancelled()
            Toast.makeText(context, "传输已取消", Toast.LENGTH_SHORT).show()
        }
        
        cleanupStreams()
    }
    
    fun getTransferStatus(): TransferStatus {
        return currentStatus
    }
    
    fun setTransferListener(listener: TransferListener) {
        this.transferListener = listener
    }
    
    private fun setStatus(status: TransferStatus) {
        currentStatus = status
        isTransferring = status.isTransferring
    }
    
    private fun cleanupStreams() {
        try {
            inputStream?.close()
            outputStream?.close()
        } catch (e: IOException) {
            Log.e(TAG, "关闭流失败", e)
        }
        inputStream = null
        outputStream = null
    }
    
    /**
     * 使用MediaStore API或文件系统保存文件
     * Android 10+ 优先使用MediaStore API保存到公共目录
     * Android 10以下使用传统文件系统
     */
    private fun saveFileUsingMediaStoreOrFileSystem(fileName: String, mimeType: String): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用MediaStore API
            saveFileUsingMediaStore(fileName, mimeType)
        } else {
            // Android 10以下使用传统方式
            saveFileToDownloadDirectory(fileName)
        }
    }
    
    /**
     * Android 10+ 使用MediaStore API保存文件
     */
    private fun saveFileUsingMediaStore(fileName: String, mimeType: String): File {
        try {
            // 获取配置的下载路径
            val downloadDir = downloadPathManager.ensureDownloadDirectoryExists()
            
            // 创建临时文件用于接收数据
            val tempFile = File(downloadDir, fileName)
            
            // 确保父目录存在
            tempFile.parentFile?.mkdirs()
            
            Log.d(TAG, "使用MediaStore保存文件到: ${tempFile.absolutePath}")
            return tempFile
        } catch (e: Exception) {
            Log.e(TAG, "使用MediaStore保存文件失败，回退到应用私有目录", e)
            return saveFileToAppPrivateDirectory(fileName)
        }
    }
    
    /**
     * Android 10以下保存文件到Download目录
     */
    private fun saveFileToDownloadDirectory(fileName: String): File {
        val downloadDir = downloadPathManager.ensureDownloadDirectoryExists()
        val file = File(downloadDir, fileName)
        
        // 确保父目录存在
        file.parentFile?.mkdirs()
        
        Log.d(TAG, "保存文件到: ${file.absolutePath}")
        return file
    }
    
    /**
     * 保存文件到应用私有目录（备用方案）
     */
    private fun saveFileToAppPrivateDirectory(fileName: String): File {
        val directory = File(context.getExternalFilesDir(null), "ReceivedFiles")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val file = File(directory, fileName)
        Log.d(TAG, "保存文件到应用私有目录: ${file.absolutePath}")
        return file
    }
    
    /**
     * 获取接收文件目录（保持向后兼容）
     */
    fun getReceivedFilesDirectory(): File {
        return downloadPathManager.ensureDownloadDirectoryExists()
    }
    
    /**
     * 获取下载路径管理器
     */
    fun getDownloadPathManager(): DownloadPathManager {
        return downloadPathManager
    }
}
