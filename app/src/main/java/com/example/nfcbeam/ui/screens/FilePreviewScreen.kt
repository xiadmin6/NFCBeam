package com.example.nfcbeam.ui.screens

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.nfcbeam.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.example.nfcbeam.ui.components.VideoThumbnail

data class MediaFile(
    val uri: Uri,
    val name: String,
    val type: String
)

@Composable
fun FilePreviewScreen(
    bluetoothDeviceName: String,
    onSendFiles: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // 状态管理
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showMediaFiles by remember { mutableStateOf(false) }
    var currentSelectionType by remember { mutableStateOf("") }
    val mediaFiles = remember { mutableStateListOf<MediaFile>() }
    
    // 文件选择启动器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFiles = listOf(it)
        }
    }
    
    val multipleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        uris?.let {
            selectedFiles = it
        }
    }
    
    // 查询媒体文件
    LaunchedEffect(showMediaFiles) {
        if (showMediaFiles) {
            mediaFiles.clear()
            when (currentSelectionType) {
                "photo" -> queryMediaFiles(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                "video" -> queryMediaFiles(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                else  -> emptyList()          // ← 加上这句
            }.forEach { mediaFile ->
                mediaFiles.add(mediaFile)
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 蓝牙连接状态显示
        ConnectionStatusHeader(bluetoothDeviceName = bluetoothDeviceName)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (!showMediaFiles) {
            // 文件类型选择按钮
            FileTypeSelectionButtons(
                onPhotoClick = {
                    currentSelectionType = "photo"
                    showMediaFiles = true
                },
                onVideoClick = {
                    currentSelectionType = "video" 
                    showMediaFiles = true
                },
                onFileClick = {
                    filePickerLauncher.launch("*/*")
                },
                onFolderClick = {
                    multipleFilePickerLauncher.launch("*/*")
                }
            )
        } else {
            // 媒体文件列表
            MediaFileList(
                mediaFiles = mediaFiles,
                onFileSelected = { mediaFile ->
                    selectedFiles = listOf(mediaFile.uri)
                    showMediaFiles = false
                },
                onBackClick = {
                    showMediaFiles = false
                    mediaFiles.clear()
                },
                selectionType = currentSelectionType
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 发送按钮（仅在选择了文件时显示）
        if (selectedFiles.isNotEmpty()) {
            Button(
                onClick = { onSendFiles(selectedFiles) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "发送文件 (${selectedFiles.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // 显示已选择的文件
        if (selectedFiles.isNotEmpty() && !showMediaFiles) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "已选择文件:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            selectedFiles.forEach { uri ->
                Text(
                    text = "• ${getFileName(context, uri)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusHeader(bluetoothDeviceName: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "手机图标",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column {
                Text(
                    text = "已连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    text = bluetoothDeviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FileTypeSelectionButtons(
    onPhotoClick: () -> Unit,
    onVideoClick: () -> Unit,
    onFileClick: () -> Unit,
    onFolderClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "选择要发送的内容",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // 第一行：照片和视频
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FileTypeButton(
                title = "照片",
                iconRes = R.drawable.ic_launcher_foreground,
                onClick = onPhotoClick,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            FileTypeButton(
                title = "视频", 
                iconRes = R.drawable.ic_launcher_foreground,
                onClick = onVideoClick,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 第二行：文件和文件夹
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FileTypeButton(
                title = "文件",
                iconRes = R.drawable.ic_launcher_foreground,
                onClick = onFileClick,
                modifier = Modifier.weight(1f)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            FileTypeButton(
                title = "文件夹",
                iconRes = R.drawable.ic_launcher_foreground,
                onClick = onFolderClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FileTypeButton(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(100.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = title,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun MediaFileList(
    mediaFiles: List<MediaFile>,
    onFileSelected: (MediaFile) -> Unit,
    onBackClick: () -> Unit,
    selectionType: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 返回按钮和标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackClick,
                modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "返回",
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = when (selectionType) {
                    "photo" -> "选择照片"
                    "video" -> "选择视频"
                    else -> "选择文件"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 文件列表
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(mediaFiles) { mediaFile ->
                MediaFileItem(
                    mediaFile = mediaFile,
                    onClick = { onFileSelected(mediaFile) }
                )
            }
        }
    }
}

@Composable
private fun MediaFileItem(
    mediaFile: MediaFile,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                mediaFile.type.startsWith("image") -> {
                    Image(
                        painter = rememberAsyncImagePainter(model = mediaFile.uri),
                        contentDescription = mediaFile.name,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                mediaFile.type.startsWith("video") -> {
                    VideoThumbnail(
                        uri = mediaFile.uri,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "文件图标",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = mediaFile.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// 辅助函数 - 需要在实际应用中实现
private fun queryMediaFiles(context: Context, contentUri: Uri): List<MediaFile> {
    val list = mutableListOf<MediaFile>()
    val contentResolver = context.contentResolver
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE
    )

    contentResolver.query(contentUri, projection, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn) ?: "unknown"
            val mime = cursor.getString(mimeColumn) ?: "unknown"
            val uri = ContentUris.withAppendedId(contentUri, id)
            list += MediaFile(uri, name, mime)
        }
    }
    return list
}
private fun getFileName(context: android.content.Context, uri: android.net.Uri): String {
    // 这里应该实现从URI获取文件名的逻辑
    return uri.lastPathSegment ?: "未知文件"
}

//@Composable
//fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier) {
//    Log.d("videoTh", "VideoThumbnail 开始加载 $uri")
//    val context = LocalContext.current
//    val retriever = remember { android.media.MediaMetadataRetriever() }
//
//    LaunchedEffect(uri) {
//        try {
//            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
//                retriever.setDataSource(pfd.fileDescriptor)
//                Log.d("VideoThumb", "FD 方式设置成功")
//            }
//        } catch (e: Exception) {
//            Log.e("VideoThumb", "FD 方式也失败：${e.message}")
//        }
//    }
//
//    val bitmap = remember {
//        try {
//            retriever.frameAtTime
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    if (bitmap != null) {
//        Image(
//            bitmap = bitmap.asImageBitmap(),
//            contentDescription = "视频缩略图",
//            modifier = modifier,
//            contentScale = ContentScale.Crop
//        )
//    } else {
//        // 如果缩略图获取失败，显示默认图标
//        Icon(
//            imageVector = Icons.Default.Videocam,
//            contentDescription = "视频文件",
//            modifier = modifier,
//            tint = MaterialTheme.colorScheme.onSurfaceVariant
//        )
//    }
//}
