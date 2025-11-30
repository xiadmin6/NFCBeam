package com.example.nfcbeam

import android.bluetooth.BluetoothSocket
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
        private const val MAX_RETRY_COUNT = 3
        private const val RETRY_DELAY_MS = 100L
        private const val SOCKET_TIMEOUT_MS = 30000
        
        // 协议命令
        private const val CMD_START_TRANSFER = 0x01
        private const val CMD_FILE_INFO = 0x02
        private const val CMD_FILE_DATA = 0x03
        private const val CMD_TRANSFER_COMPLETE = 0x04
        private const val CMD_TRANSFER_ERROR = 0x05
        private const val CMD_CANCEL_TRANSFER = 0x06
        private const val CMD_ACK = 0x07
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
            Log.e(TAG, "蓝牙未连接，无法开始传输")
            mainHandler.post {
                transferListener?.onTransferError("蓝牙未连接")
                Toast.makeText(context, "蓝牙未连接", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        pendingFiles.clear()
        pendingFiles.addAll(fileUris)
        totalFiles = fileUris.size
        
        // 计算总文件大小
        totalFileSize = calculateTotalFileSize(fileUris)
        
        // 重置传输状态
        totalTransferredBytes = 0L
        currentFileIndex = 0
        isTransferring = true
        
        Log.d(TAG, "开始文件传输: 文件数=$totalFiles, 总大小=$totalFileSize")
        
        // 通知监听器传输开始
        mainHandler.post {
            transferListener?.onTransferStarted(totalFiles, totalFileSize)
        }
        
        executor.execute {
            performFileTransfer(socket)
        }
    }
    
    private fun performFileTransfer(socket: BluetoothSocket) {
        try {
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
                if (!isTransferring) {
                    Log.d(TAG, "传输被取消")
                    break
                }
                currentFileIndex = index + 1
                transferSingleFile(fileUri, index)
            }
            
            if (isTransferring) {
                // 发送传输完成命令
                sendTransferCompleteCommand()
                setStatus(TransferStatus(isCompleted = true, isSuccess = true))
                
                mainHandler.post {
                    transferListener?.onTransferCompleted()
                    Toast.makeText(context, "文件传输完成", Toast.LENGTH_LONG).show()
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "文件传输失败", e)
            setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message ?: "未知错误"))
            
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
            // 注意：这里不关闭流，保持Socket连接可用
            // 只有在用户明确退出或需要断开连接时才关闭
        }
    }
    
    private fun transferSingleFile(fileUri: Uri, fileIndex: Int) {
        try {
            val fileInfo = getFileInfo(fileUri)
            if (fileInfo == null) {
                Log.e(TAG, "❌ 无法获取文件信息: $fileUri")
                throw IOException("无法获取文件信息")
            }
            
            Log.d(TAG, "📤 开始传输文件 ${fileIndex + 1}/$totalFiles: ${fileInfo.fileName}, 大小: ${fileInfo.fileSize} 字节")
            
            if (fileInfo.fileSize == 0L) {
                Log.w(TAG, "⚠️ 警告：文件大小为0，跳过传输")
                return
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
            
            // 读取文件内容
            val contentResolver: ContentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(fileUri)
            if (inputStream == null) {
                Log.e(TAG, "❌ 无法打开文件输入流: $fileUri")
                throw IOException("无法打开文件输入流")
            }
            
            inputStream.use { fileStream ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                var totalBytesRead = 0L
                var chunkCount = 0
                
                Log.d(TAG, "📦 开始读取并发送文件数据...")
                
                while (fileStream.read(buffer).also { bytesRead = it } != -1 && isTransferring) {
                    if (bytesRead > 0) {
                        // 发送文件数据命令
                        sendFileData(buffer, bytesRead)
                        
                        totalBytesRead += bytesRead
                        totalTransferredBytes += bytesRead
                        chunkCount++
                        
                        // 每100个数据块记录一次日志
                        if (chunkCount % 100 == 0) {
                            Log.d(TAG, "📊 已发送 $chunkCount 个数据块，总计 $totalBytesRead/${fileInfo.fileSize} 字节 (${(totalBytesRead * 100 / fileInfo.fileSize)}%)")
                        }
                        
                        // 更新进度
                        mainHandler.post {
                            transferListener?.onTransferProgress(
                                totalTransferredBytes,
                                totalFileSize,
                                fileIndex + 1,
                                totalFiles
                            )
                        }
                        
                        // 添加小延迟以确保数据可靠传输
                        Thread.sleep(1)
                    }
                }
                
                Log.d(TAG, "✅ 文件传输完成: ${fileInfo.fileName}, 实际发送: $totalBytesRead 字节, 预期: ${fileInfo.fileSize} 字节, 数据块数: $chunkCount")
                
                if (totalBytesRead != fileInfo.fileSize) {
                    Log.w(TAG, "⚠️ 警告：实际发送字节数与文件大小不匹配！")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 传输单个文件失败", e)
            throw e
        }
    }
    
    private fun getFileInfo(fileUri: Uri): FileInfo? {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            
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
            
            // 获取MIME类型
            val mimeType = contentResolver.getType(fileUri) ?: "application/octet-stream"
            
            Log.d(TAG, "获取文件信息: 文件名=$fileName, 大小=$fileSize, 类型=$mimeType")
            
            // 如果文件大小为0，尝试通过输入流获取
            if (fileSize == 0L) {
                try {
                    contentResolver.openInputStream(fileUri)?.use { inputStream ->
                        fileSize = inputStream.available().toLong()
                        Log.d(TAG, "通过输入流获取文件大小: $fileSize")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法通过输入流获取文件大小", e)
                }
            }
            
            return FileInfo(
                fileName = fileName,
                fileSize = fileSize,
                fileType = mimeType
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取文件信息失败: ${fileUri}", e)
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
    
    private fun readWithRetry(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var retryCount = 0
        while (retryCount < MAX_RETRY_COUNT) {
            try {
                val bytesRead = inputStream.read(buffer, offset, length)
                if (bytesRead > 0) {
                    return bytesRead // 读取成功
                } else if (bytesRead == -1) {
                    throw IOException("连接已断开")
                }
                // 如果bytesRead == 0，继续重试
            } catch (e: IOException) {
                retryCount++
                Log.w(TAG, "读取数据失败，重试 $retryCount/$MAX_RETRY_COUNT: ${e.message}")
                if (retryCount >= MAX_RETRY_COUNT) {
                    throw e // 达到最大重试次数，抛出异常
                }
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return 0
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "文件接收失败", e)
                setStatus(TransferStatus(isCompleted = true, isSuccess = false, errorMessage = e.message ?: "接收失败"))
                mainHandler.post {
                    transferListener?.onTransferError(e.message ?: "接收失败")
                    Toast.makeText(context, "文件接收失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                isTransferring = false
            }
        }
    }
    
    private fun receiveFiles(socket: BluetoothSocket) {
        try {
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
                        
                        // 读取文件信息 - 确保读取完整的数据
                        val fileSizeBytes = ByteArray(8)
                        val fileSizeRead = inputStream?.read(fileSizeBytes) ?: 0
                        if (fileSizeRead != 8) {
                            Log.e(TAG, "读取文件大小失败，期望8字节，实际读取 $fileSizeRead 字节")
                            break
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
                        val fileNameLengthRead = inputStream?.read(fileNameLengthBytes) ?: 0
                        if (fileNameLengthRead != 2) {
                            Log.e(TAG, "读取文件名长度失败，期望2字节，实际读取 $fileNameLengthRead 字节")
                            break
                        }
                        val fileNameLength = (fileNameLengthBytes[0].toInt() and 0xFF shl 8) or
                                           (fileNameLengthBytes[1].toInt() and 0xFF)
                        
                        val fileTypeLengthBytes = ByteArray(2)
                        val fileTypeLengthRead = inputStream?.read(fileTypeLengthBytes) ?: 0
                        if (fileTypeLengthRead != 2) {
                            Log.e(TAG, "读取文件类型长度失败，期望2字节，实际读取 $fileTypeLengthRead 字节")
                            break
                        }
                        val fileTypeLength = (fileTypeLengthBytes[0].toInt() and 0xFF shl 8) or
                                           (fileTypeLengthBytes[1].toInt() and 0xFF)
                        
                        // 读取文件名
                        val fileNameBytes = ByteArray(fileNameLength)
                        var fileNameBytesRead = 0
                        while (fileNameBytesRead < fileNameLength) {
                            val read = inputStream?.read(fileNameBytes, fileNameBytesRead, fileNameLength - fileNameBytesRead) ?: 0
                            if (read <= 0) break
                            fileNameBytesRead += read
                        }
                        if (fileNameBytesRead != fileNameLength) {
                            Log.e(TAG, "读取文件名失败，期望 $fileNameLength 字节，实际读取 $fileNameBytesRead 字节")
                            break
                        }
                        currentFileName = String(fileNameBytes, Charsets.UTF_8)
                        
                        // 读取文件类型
                        val fileTypeBytes = ByteArray(fileTypeLength)
                        var fileTypeBytesRead = 0
                        while (fileTypeBytesRead < fileTypeLength) {
                            val read = inputStream?.read(fileTypeBytes, fileTypeBytesRead, fileTypeLength - fileTypeBytesRead) ?: 0
                            if (read <= 0) break
                            fileTypeBytesRead += read
                        }
                        if (fileTypeBytesRead != fileTypeLength) {
                            Log.e(TAG, "读取文件类型失败，期望 $fileTypeLength 字节，实际读取 $fileTypeBytesRead 字节")
                            break
                        }
                        currentFileType = String(fileTypeBytes, Charsets.UTF_8)
                        
                        currentFileIndex++
                        Log.d(TAG, "接收文件信息: $currentFileName, 大小: $currentFileSize, 类型: $currentFileType, 当前文件: $currentFileIndex/$totalFiles")
                        
                        val fileInfo = FileInfo(currentFileName!!, currentFileSize, currentFileType!!)
                        mainHandler.post {
                            transferListener?.onFileTransferStarted(fileInfo, currentFileIndex, totalFiles)
                        }
                        
                        // 准备接收文件数据
                        val directory = getReceivedFilesDirectory()
                        val file = File(directory, currentFileName!!)
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
                        
                        // 读取数据长度
                        val lengthBytes = ByteArray(4)
                        val lengthRead = inputStream?.read(lengthBytes)
                        if (lengthRead != 4) {
                            Log.e(TAG, "❌ 读取数据长度失败，期望4字节，实际读取 $lengthRead 字节")
                            break
                        }
                        
                        val dataLength = (lengthBytes[0].toInt() and 0xFF shl 24) or
                                       (lengthBytes[1].toInt() and 0xFF shl 16) or
                                       (lengthBytes[2].toInt() and 0xFF shl 8) or
                                       (lengthBytes[3].toInt() and 0xFF)
                        
                        if (dataLength <= 0 || dataLength > BUFFER_SIZE * 2) {
                            Log.e(TAG, "❌ 数据长度异常: $dataLength")
                            break
                        }
                        
                        // 读取数据
                        val buffer = ByteArray(minOf(dataLength, BUFFER_SIZE))
                        var bytesRead = 0
                        while (bytesRead < dataLength) {
                            val remaining = dataLength - bytesRead
                            val chunkSize = minOf(remaining, buffer.size)
                            val read = inputStream?.read(buffer, 0, chunkSize) ?: -1
                            if (read <= 0) {
                                Log.e(TAG, "❌ 读取数据失败，已读取 $bytesRead/$dataLength 字节")
                                break
                            }
                            
                            fileOutputStream?.write(buffer, 0, read)
                            bytesRead += read
                            totalBytesReceived += read
                            
                            // 更新进度
                            mainHandler.post {
                                transferListener?.onTransferProgress(
                                    totalBytesReceived,
                                    currentFileSize,
                                    currentFileIndex,
                                    totalFiles
                                )
                            }
                        }
                        
                        if (bytesRead != dataLength) {
                            Log.e(TAG, "❌ 数据块接收不完整: 期望 $dataLength 字节，实际接收 $bytesRead 字节")
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
                            transferListener?.onTransferCompleted()
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
    
    fun getReceivedFilesDirectory(): File {
        val directory = File(context.getExternalFilesDir(null), "ReceivedFiles")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }
}
