package com.example.nfcbeam.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nfcbeam.DownloadPathManager
import com.example.nfcbeam.Screen
import com.example.nfcbeam.ui.theme.NFCBeamTheme
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onSendFiles: () -> Unit,
    onReceiveFiles: () -> Unit,
    onToggleMode: () -> Unit,
    onBluetoothPairing: () -> Unit,
    isNfcConnected: Boolean = false,
    isSenderMode: Boolean = true,
    currentScreen: Screen = Screen.HOME,
    currentDownloadLocation: DownloadPathManager.Companion.DownloadLocation = DownloadPathManager.Companion.DownloadLocation.DOWNLOADS,
    onDownloadLocationChange: (DownloadPathManager.Companion.DownloadLocation) -> Unit = {},
    customPathDisplayName: String = "未设置自定义目录",
    onCustomFolderPicker: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDownloadPathDialog by remember { mutableStateOf(false) }
    
    // 蓝牙连接状态检测 - 只有在 HOME 页面且已连接时才自动进入下一阶段
    LaunchedEffect(isNfcConnected, currentScreen) {
        if (isNfcConnected && currentScreen == Screen.HOME) {
            // 蓝牙连接建立后，延迟500ms后自动进入文件选择界面
            delay(500)
            if (isSenderMode) {
                onSendFiles()
            } else {
                onReceiveFiles()
            }
        }
    }
    
    Scaffold { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 顶部栏：左侧app名称，右侧下载目录按钮
//            Row(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .padding(16.dp),
//                horizontalArrangement = Arrangement.SpaceBetween,
//                verticalAlignment = Alignment.CenterVertically
//            ) {
//                Text(
//                    text = "NFCBeam",
//                    style = MaterialTheme.typography.titleMedium.copy(
//                        fontWeight = FontWeight.Bold
//                    ),
//                    color = MaterialTheme.colorScheme.primary,
//                )
//
                // 下载目录选择按钮
//                IconButton(
//                    onClick = { showDownloadPathDialog = true }
//                ) {
//                    Icon(
//                        imageVector = Icons.Default.Folder,
//                        contentDescription = "选择下载目录",
//                        tint = MaterialTheme.colorScheme.primary,
//                        modifier = Modifier.size(36.dp)
//                    )
//                }
//            }
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 呼吸灯动画
                val infiniteTransition = rememberInfiniteTransition(label = "breathing")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.6f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )
                
                // 水波纹效果 - 3层
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "ocean_waves")

                    val waveCount = 3
                    val baseDuration = 4000
                    val baseAlpha = 0.15f

                    val waveScales = List(waveCount) { index ->
                        infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 3.0f, // 扩散到3倍大小
                            animationSpec = infiniteRepeatable(
                                animation = tween(
                                    durationMillis = baseDuration + (index * 500), // 每层波浪周期稍长，增加变化
                                    easing = LinearEasing // 线性扩散，更像海浪
                                ),
                                repeatMode = RepeatMode.Restart, // 动画结束后立即重新开始
                                initialStartOffset = StartOffset(index * (baseDuration / waveCount)) // 错开启动时间
                            ),
                            label = "wave_scale_$index"
                        ).value
                    }

                    for (i in 0 until waveCount) {
                        Box(
                            modifier = Modifier
                                .size(200.dp) // 基准尺寸与中心圆一致
                                .scale(waveScales[i]) // 应用当前波浪的缩放比例
                                .clip(CircleShape)
                                // 关键：透明度随缩放比例增大而减小，形成消散效果
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = (baseAlpha * (1f - (waveScales[i] / 3f))).coerceAtLeast(0f)
                                    )
                                )
                        )
                    }
                    
                    // 主圆圈
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            // 修改如下两行来禁用点击反馈
                            .clickable(
                                indication = null, // 禁用默认的波纹效果
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onToggleMode() }
                            )
                            .then(
                                if (isSenderMode) {
                                    Modifier.scale(scale)
                                } else {
                                    Modifier.scale(scale)
                                }
                            )
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        // 外层：模拟发光边框（随呼吸动画缩放）
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .scale(scale * 1.05f) // 略大于内层，形成边框感
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha * 0.4f))
                        )

                        // 内层：主填充区域
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        )

                        // 文字内容
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isSenderMode) "发送" else "接收",
                                style = TextStyle(
                                    fontSize = 35.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = "点击切换",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 应用标题
                Text(
                    text = "NFCBeam",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // 蓝牙连接状态指示器
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = if (isNfcConnected) "✓ 蓝牙连接已建立" else "等待蓝牙OOB配对...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isNfcConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    
                    if (isNfcConnected) {
                        Text(
                            text = "正在自动进入${if (isSenderMode) "文件选择" else "文件接收"}界面...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .clickable { showDownloadPathDialog = true } // 点击打开对话框
                ) {
                    Text(
                        text = "保存目录：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when (currentDownloadLocation) {
                            DownloadPathManager.Companion.DownloadLocation.DOWNLOADS -> "下载目录"
                            DownloadPathManager.Companion.DownloadLocation.DOCUMENTS -> "文档目录"
                            DownloadPathManager.Companion.DownloadLocation.PICTURES -> "图片目录"
                            DownloadPathManager.Companion.DownloadLocation.MOVIES -> "视频目录"
                            DownloadPathManager.Companion.DownloadLocation.MUSIC -> "音乐目录"
                            DownloadPathManager.Companion.DownloadLocation.CUSTOM -> customPathDisplayName
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "保存目录",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                
//                 手动操作按钮（在蓝牙未连接时显示）
//                if (!isNfcConnected) {
//                    // 蓝牙配对按钮 - 用于触发蓝牙OOB配对
//                    ActionButton(
//                        icon = Icons.Default.Bluetooth,
//                        text = "蓝牙配对",
//                        description = if (isSenderMode) "开始蓝牙OOB配对发送" else "开始蓝牙OOB配对接收",
//                        onClick = onBluetoothPairing,
//                        modifier = Modifier.size(32.dp)
//                    )
//
//                    Spacer(modifier = Modifier.height(16.dp))
//
//                    ActionButton(
//                        icon = Icons.Default.Send,
//                        text = "发送文件",
//                        description = "选择并发送文件",
//                        onClick = onSendFiles,
//                        modifier = Modifier.size(32.dp)
//                    )
//
//                    Spacer(modifier = Modifier.height(16.dp))
//
//                    ActionButton(
//                        icon = Icons.Default.Wifi,
//                        text = "接收文件",
//                        description = "等待接收文件",
//                        onClick = onReceiveFiles,
//                        modifier = Modifier.size(32.dp)
//                    )
//                }
            }
        }
        
        // 下载路径选择对话框
        if (showDownloadPathDialog) {
            DownloadPathSelectionDialog(
                currentLocation = currentDownloadLocation,
                customPathDisplayName = customPathDisplayName,
                onLocationSelected = { location ->
                    onDownloadLocationChange(location)
                    showDownloadPathDialog = false
                },
                onCustomFolderPicker = {
                    showDownloadPathDialog = false
                    onCustomFolderPicker()
                },
                onDismiss = { showDownloadPathDialog = false }
            )
        }
    }
}

@Composable
fun DownloadPathSelectionDialog(
    currentLocation: DownloadPathManager.Companion.DownloadLocation,
    customPathDisplayName: String,
    onLocationSelected: (DownloadPathManager.Companion.DownloadLocation) -> Unit,
    onCustomFolderPicker: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "选择下载目录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "接收的文件将保存到所选目录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                DownloadPathManager.Companion.DownloadLocation.values().forEach { location ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                if (location == DownloadPathManager.Companion.DownloadLocation.CUSTOM) {
                                    onCustomFolderPicker()
                                } else {
                                    onLocationSelected(location)
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (location == currentLocation) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = location == currentLocation,
                                onClick = {
                                    if (location == DownloadPathManager.Companion.DownloadLocation.CUSTOM) {
                                        onCustomFolderPicker()
                                    } else {
                                        onLocationSelected(location)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = location.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (location == currentLocation) FontWeight.Bold else FontWeight.Normal
                                )
                                // 显示自定义路径的详细信息
                                if (location == DownloadPathManager.Companion.DownloadLocation.CUSTOM) {
                                    Text(
                                        text = customPathDisplayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // 自定义目录显示文件夹图标
                            if (location == DownloadPathManager.Companion.DownloadLocation.CUSTOM) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "选择文件夹",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    text: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    NFCBeamTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            HomeScreen(
                onSendFiles = {},
                onReceiveFiles = {},
                onToggleMode = {},
                onBluetoothPairing = {},
                isNfcConnected = false
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenConnectedPreview() {
    NFCBeamTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            HomeScreen(
                onSendFiles = {},
                onReceiveFiles = {},
                onToggleMode = {},
                onBluetoothPairing = {},
                isNfcConnected = true
            )
        }
    }
}
