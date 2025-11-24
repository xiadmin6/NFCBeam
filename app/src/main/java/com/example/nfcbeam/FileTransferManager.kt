package com.example.nfcbeam

import android.bluetooth.BluetoothSocket
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class FileTransferManager(private val context: Context, private val bluetoothManager: BluetoothManager) {
    
    companion object {
        private const val TAG = "FileTransferManager"
        private const val BUFFER_SIZE = 8192
        private const val PROTOCOL_VERSION = 1
        
        // 协议命令
        private const val CMD_START_TRANSFER = 0x01
        private const val CMD_FILE_INFO = 0x02
        private const val CMD_FILE_DATA = 0x03
        private const val CMD_TRANSFER_COMPLETE = 0x04
        private const val CMD_TRANSFER_ERROR = 0x05
        private const val CMD_CANCEL_TRANSFER = 0x06
    }
    
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
    
    // 传输状态监听器
    interface TransferListener {
        fun onTransferStarted(totalFiles: Int, totalSize: Long)
        fun onFileTransferStarted(fileInfo: FileInfo, currentFile: Int, totalFiles: Int)
        fun onTransferProgress(transferredBytes: Long, totalBytes: Long, currentFile: Int, totalFiles: Int)
        fun onTransferCompleted()
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
    
    fun startFileTransfer(fileUris: List<Uri>) {
        if (isTransferring) {
            Toast.makeText(context, "传输正在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        
        val socket = bluetoothManager.getClientSocket()
        if (socket == null || !socket.isConnected) {
            Toast.makeText(context, "蓝牙未连接", Toast.LENGTH_SHORT).show()
            return
        }
        
        pendingFiles.clear()
        pendingFiles.addAll(fileUris)
        totalFiles = fileUris.size
        
        // 计算总文件大小
        totalFileSize = calculateTotalFileSize(fileUris)
        
        executor.execute {
            performFileTransfer(socket)
        }
    }
    
    private fun performFileTransfer(socket: BluetoothSocket) {
        try {
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            
            setStatus(TransferStatus(isConnecting = true))
            
            // 发送开始传输命令
            sendStartTransferCommand()
            
            // 逐个传输文件
            for ((index, fileUri) in pendingFiles.withIndex()) {
                currentFileIndex = index + 1
                transferSingleFile(fileUri, index)
            }
            
            // 发送传输完成命令
            sendTransferCompleteCommand()
            setStatus(TransferStatus(isCompleted = true, isSuccess = true))
            
            mainHandler.post {
                transferListener?.onTransferCompleted()
                Toast.makeText(context, "文件传输完成", Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "文件传输失败", e)
            setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message ?: "未知错误"))
            sendTransferErrorCommand(e.message ?: "未知错误")
            
            mainHandler.post {
                transferListener?.onTransferError(e.message ?: "传输失败")
                Toast.makeText(context, "文件传输失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            isTransferring = false
            cleanupStreams()
        }
    }
    
    private fun transferSingleFile(fileUri: Uri, fileIndex: Int) {
        try {
            val fileInfo = getFileInfo(fileUri)
            if (fileInfo == null) {
                Log.e(TAG, "无法获取文件信息: $fileUri")
                return
            }
            
            setStatus(TransferStatus(isTransferring = true, currentFileName = fileInfo.fileName))
            
            // 发送文件信息
            sendFileInfo(fileInfo)
            
            setStatus(TransferStatus(isTransferring = true, currentFileName = fileInfo.fileName))
            mainHandler.post {
                transferListener?.onFileTransferStarted(fileInfo, fileIndex + 1, totalFiles)
            }
            
            // 读取文件内容
            val contentResolver: ContentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(fileUri)
            inputStream?.use { fileStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalBytesRead = 0L
                
                while (fileStream.read(buffer).also { bytesRead = it } != -1 && isTransferring) {
                    // 发送文件数据命令
                    sendFileData(buffer, bytesRead)
                    
                    totalBytesRead += bytesRead
                    totalTransferredBytes += bytesRead
                    
                    // 更新进度
                    mainHandler.post {
                        transferListener?.onTransferProgress(
                            totalTransferredBytes, 
                            totalFileSize, 
                            fileIndex + 1, 
                            totalFiles
                        )
                    }
                    
                    // 添加小延迟以避免过快传输
                    Thread.sleep(10)
                }
                
                Log.d(TAG, "文件传输完成: ${fileInfo.fileName}, 大小: $totalBytesRead 字节")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "传输单个文件失败", e)
            throw e
        }
    }
    
    private fun getFileInfo(fileUri: Uri): FileInfo? {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val cursor = contentResolver.query(fileUri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayName = it.getString(it.getColumnIndexOrThrow("_display_name"))
                    val size = it.getLong(it.getColumnIndexOrThrow("_size"))
                    val mimeType = it.getString(it.getColumnIndexOrThrow("mime_type"))
                    
                    return FileInfo(
                        fileName = displayName ?: "unknown",
                        fileSize = size,
                        fileType = mimeType ?: "application/octet-stream"
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取文件信息失败", e)
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
            
            val header = ByteArray(13 + fileNameBytes.size + fileTypeBytes.size)
            header[0] = CMD_FILE_INFO.toByte()
            
            // 文件大小 (8 bytes)
            for (i in 0 until 8) {
                header[1 + i] = (fileInfo.fileSize shr (56 - i * 8)).toByte()
            }
            
            // 文件名长度 (2 bytes)
            header[9] = (fileNameBytes.size shr 8).toByte()
            header[10] = fileNameBytes.size.toByte()
            
            // 文件类型长度 (2 bytes)
            header[11] = (fileTypeBytes.size shr 8).toByte()
            header[12] = fileTypeBytes.size.toByte()
            
            // 文件名和文件类型
            System.arraycopy(fileNameBytes, 0, header, 13, fileNameBytes.size)
            System.arraycopy(fileTypeBytes, 0, header, 13 + fileNameBytes.size, fileTypeBytes.size)
            
            os.write(header)
            os.flush()
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
            
            os.write(header)
            os.write(buffer, 0, length)
            os.flush()
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
        executor.execute {
            try {
                val socket = bluetoothManager.getClientSocket()
                if (socket != null && socket.isConnected) {
                    receiveFiles(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "文件接收失败", e)
            setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message ?: "接收失败"))
                mainHandler.post {
                    transferListener?.onTransferError(e.message ?: "接收失败")
                }
            }
        }
    }
    
    private fun receiveFiles(socket: BluetoothSocket) {
        // 实现文件接收逻辑
        // 这里需要解析协议命令并保存接收到的文件
        setStatus(TransferStatus(isTransferring = true, currentFileName = "接收文件"))
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
    
    fun getReceivedFilesDirectory(): File {
        val directory = File(context.getExternalFilesDir(null), "ReceivedFiles")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }
}
