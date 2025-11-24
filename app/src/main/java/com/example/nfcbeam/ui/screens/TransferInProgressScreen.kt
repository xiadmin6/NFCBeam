package com.example.nfcbeam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.nfcbeam.FileTransferManager
import com.example.nfcbeam.ui.theme.NFCBeamTheme

@Composable
fun TransferInProgressScreen(
    transferStatus: FileTransferManager.TransferStatus,
    onCancel: () -> Unit,
    onTransferComplete: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 监听传输完成
    LaunchedEffect(transferStatus) {
        if (transferStatus.isCompleted) {
            onTransferComplete(transferStatus.isSuccess)
        }
    }

    Scaffold { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 传输状态指示器
            when {
                transferStatus.isConnecting -> {
                    TransferState(
                        icon = Icons.Default.Nfc,
                        title = "NFC配对中",
                        description = "请将设备靠近接收方进行NFC握手",
                        showProgress = true,
                        progressType = ProgressType.Indeterminate
                    )
                }
                transferStatus.isTransferring -> {
                    TransferState(
                        icon = Icons.Default.Bluetooth,
                        title = "传输中",
                        description = "通过蓝牙传输文件",
                        showProgress = true,
                        progressType = ProgressType.Determinate(transferStatus.progress),
                        progressText = "${(transferStatus.progress * 100).toInt()}%"
                    )
                }
                transferStatus.isCompleted -> {
                    if (transferStatus.isSuccess) {
                        TransferState(
                            icon = Icons.Default.Wifi,
                            title = "传输完成",
                            description = "文件传输成功",
                            showProgress = false
                        )
                    } else {
                        TransferState(
                            icon = Icons.Default.Cancel,
                            title = "传输失败",
                            description = transferStatus.errorMessage ?: "未知错误",
                            showProgress = false
                        )
                    }
                }
                else -> {
                    TransferState(
                        icon = Icons.Default.Bluetooth,
                        title = "等待连接",
                        description = "准备建立蓝牙连接",
                        showProgress = true,
                        progressType = ProgressType.Indeterminate
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // 传输详情
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "传输详情",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    TransferDetailItem(
                        label = "状态",
                        value = when {
                            transferStatus.isConnecting -> "NFC配对中"
                            transferStatus.isTransferring -> "传输中"
                            transferStatus.isCompleted && transferStatus.isSuccess -> "完成"
                            transferStatus.isCompleted -> "失败"
                            else -> "等待"
                        }
                    )
                    
                    if (transferStatus.isTransferring || transferStatus.isCompleted) {
                        TransferDetailItem(
                            label = "进度",
                            value = "${(transferStatus.progress * 100).toInt()}%"
                        )
                        
                        TransferDetailItem(
                            label = "当前文件",
                            value = transferStatus.currentFileName ?: "未知"
                        )
                        
                        TransferDetailItem(
                            label = "已传输",
                            value = "${transferStatus.transferredFiles}/${transferStatus.totalFiles}"
                        )
                    }
                    
                    if (transferStatus.errorMessage != null) {
                        TransferDetailItem(
                            label = "错误信息",
                            value = transferStatus.errorMessage!!
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 取消按钮（仅在传输中显示）
            if (!transferStatus.isCompleted) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("取消传输")
                }
            }

            // 传输提示
            Text(
                text = when {
                    transferStatus.isConnecting -> 
                        "请确保：\n" +
                        "• 两台设备NFC功能已开启\n" +
                        "• 设备背面对准进行NFC握手\n" +
                        "• 保持设备靠近直到连接建立"
                    transferStatus.isTransferring -> 
                        "传输过程中：\n" +
                        "• 保持设备在蓝牙范围内\n" +
                        "• 避免设备锁屏\n" +
                        "• 不要关闭应用"
                    else -> "传输状态监控中"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    PaddingValues(
                        start  = 16.dp,
                        top    = 24.dp,
                        end    = 16.dp,
                        bottom = 0.dp
                    )
                )
            )
        }
    }
}

@Composable
private fun TransferState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    showProgress: Boolean,
    progressType: ProgressType = ProgressType.Indeterminate,
    progressText: String? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        if (showProgress) {
            when (progressType) {
                is ProgressType.Indeterminate -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                }
                is ProgressType.Determinate -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { progressType.progress },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        )
                        progressText?.let { text ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferDetailItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

sealed class ProgressType {
    object Indeterminate : ProgressType()
    data class Determinate(val progress: Float) : ProgressType()
}

@Preview(showBackground = true)
@Composable
fun TransferInProgressScreenPreview() {
    NFCBeamTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            TransferInProgressScreen(
                transferStatus = FileTransferManager.TransferStatus(
                    isConnecting = true,
                    isTransferring = false,
                    isCompleted = false,
                    progress = 0f,
                    totalFiles = 3,
                    transferredFiles = 0,
                    currentFileName = null,
                    errorMessage = null
                ),
                onCancel = {},
                onTransferComplete = {}
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TransferInProgressScreenTransferringPreview() {
    NFCBeamTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            TransferInProgressScreen(
                transferStatus = FileTransferManager.TransferStatus(
                    isConnecting = false,
                    isTransferring = true,
                    isCompleted = false,
                    progress = 0.65f,
                    totalFiles = 3,
                    transferredFiles = 1,
                    currentFileName = "photo.jpg",
                    errorMessage = null
                ),
                onCancel = {},
                onTransferComplete = {}
            )
        }
    }
}
