package com.example.nfcbeam.ui.screens

import android.net.Uri
import android.provider.MediaStore
import android.content.ContentResolver
import android.util.Log
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import com.example.nfcbeam.ui.components.VideoThumbnail

/* -------------------- 主页面 -------------------- */
@Composable
fun FileSelectPage(
    bluetoothDeviceName: String,
    selectedFiles: List<Uri>,
    onPhotoPicker: () -> Unit,
    onVideoPicker: () -> Unit,
    onFilePicker: (mimeTypes: Array<String>) -> Unit,
    onFolderPicker: () -> Unit,
    onSend: (List<Uri>) -> Unit,
    onFileSelectionChange: (List<Uri>) -> Unit, // 新增：处理文件选择状态变化
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    val allImages = remember { getLocalImages(contentResolver) }
    val allVideos = remember { getLocalVideos(contentResolver) }

    var page by remember { mutableStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val barTotalHeight = 96.dp
        val downOffset = barTotalHeight / 3

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .offset(y = downOffset)          // ← 整体正偏移
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF2A5364),   // 深蓝
                                Color(0xFF1F2A48)    // 深紫
                            ),
                            start = Offset(0f, 0f),
                            end   = Offset.Infinite
                        )
                    )
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 手机图标 + 光晕
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(0.15f))
                            .border(1.dp, Color.White.copy(0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = "手机图标",
                            modifier = Modifier.size(24.dp),
                            tint = Color.White
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = bluetoothDeviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // 右侧状态指示灯
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                            .border(2.dp, Color.White.copy(0.8f), CircleShape)
                    )
                }
            }
        }

        /* 功能选择行 - 照片、视频、文件、文件夹 */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            FileTypeOption(
                title = "照片",
                icon = Icons.Default.Image,
                onClick = { page = 0 },
                modifier = Modifier.weight(1f)
            )

            FileTypeOption(
                title = "视频",
                icon = Icons.Default.Videocam,
                onClick = { page = 1 },
                modifier = Modifier.weight(1f)
            )

            FileTypeOption(
                title = "文件",
                icon = Icons.Default.Description,
                onClick = { onFilePicker(arrayOf("*/*")) },  // 保持原逻辑
                modifier = Modifier.weight(1f)
            )

            FileTypeOption(
                title = "文件夹",
                icon = Icons.Default.Folder,
                onClick = { onFilePicker(arrayOf("*/*"))  },     // 如果你想接文件夹选择器
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        /* 当 page=0 照片 / page=1 视频 */
        when (page) {
            0 -> MediaGrid(allImages, selectedFiles, onFileSelectionChange)
            1 -> MediaGrid(allVideos, selectedFiles, onFileSelectionChange)
        }

        // 添加底部间距，为固定发送按钮留出空间
        Spacer(modifier = Modifier.height(80.dp))
        }
        
        // 固定底部的发送按钮
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            val density = LocalDensity.current
            val maskHeight = 320.dp
            val maskHeightPx = with(density) { maskHeight.toPx() }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maskHeight)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                            ),
                            startY = 0f,
                            endY = maskHeightPx
                        )
                    )
            )

            // 2. 发送按钮（悬浮）
            val buttonHeight = 56.dp * 4 / 3          // 当前按钮高
            val downOffset = buttonHeight / 4         // ↓ 下移 1/4

            Card(
                onClick = { if (selectedFiles.isNotEmpty()) onSend(selectedFiles) },
                enabled = selectedFiles.isNotEmpty(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedFiles.isNotEmpty())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (selectedFiles.isNotEmpty()) 16.dp else 0.dp
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp + downOffset)   // ← 这里实现下移
                    .fillMaxWidth(0.9f)
                    .height(buttonHeight)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("发送", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

data class MediaItem(
    val uri: Uri,
    val mimeType: String?
)

@Composable
private fun SelectedFileCard(uri: Uri) {
    Card(
        modifier = Modifier
            .width(80.dp)
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 根据文件类型显示不同的图标
                val (icon, iconDescription) = getFileTypeIcon(uri)
                
                Icon(
                    imageVector = icon,
                    contentDescription = iconDescription,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // 显示文件名（截取最后部分）
                val fileName = getFileNameFromUri(uri)
                Text(
                    text = fileName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun FileTypeOption(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun FileTypeCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// 根据URI获取文件类型图标
private fun getFileTypeIcon(uri: Uri): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> {
    val path = uri.toString().lowercase()
    return when {
        path.contains(".jpg") || path.contains(".jpeg") || path.contains(".png") || 
        path.contains(".gif") || path.contains(".bmp") || path.contains(".webp") -> 
            Pair(Icons.Default.Image, "图片文件")
        
        path.contains(".mp4") || path.contains(".avi") || path.contains(".mov") || 
        path.contains(".mkv") || path.contains(".wmv") || path.contains(".flv") -> 
            Pair(Icons.Default.Videocam, "视频文件")
        
        path.contains(".pdf") || path.contains(".doc") || path.contains(".docx") || 
        path.contains(".txt") || path.contains(".xls") || path.contains(".xlsx") -> 
            Pair(Icons.Default.Description, "文档文件")
        
        else -> Pair(Icons.Default.Folder, "未知文件")
    }
}

// 从URI中提取文件名
private fun getFileNameFromUri(uri: Uri): String {
    val path = uri.toString()
    val lastSlash = path.lastIndexOf('/')
    val fileName = if (lastSlash != -1 && lastSlash + 1 < path.length) {
        path.substring(lastSlash + 1)
    } else {
        path
    }
    
    // 如果文件名太长，截断显示
    return if (fileName.length > 15) {
        fileName.substring(0, 12) + "..."
    } else {
        fileName
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selectedFiles: List<Uri>,
    onFileSelectionChange: (List<Uri>) -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("无媒体文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(items) { item ->
            MediaItemCard(
                item = item,
                isSelected = selectedFiles.contains(item.uri),
                onClick = { 
                    // 多选逻辑：如果已选中则移除，否则添加
                    val newSelectedFiles = if (selectedFiles.contains(item.uri)) {
                        selectedFiles.filter { it != item.uri }
                    } else {
                        selectedFiles + item.uri
                    }
                    // 调用状态更新函数
                    onFileSelectionChange(newSelectedFiles)
                }
            )
        }
    }
}

@Composable
private fun MediaItemCard(
    item: MediaItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 用 mimeType 判断，不再看后缀
    val isVideo = remember(item) { item.mimeType?.startsWith("video/") == true }
    Log.d("videocard", "uri=${item.uri}  isVideo=$isVideo")

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer 
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) 
        else null
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            if (isVideo) {
                VideoThumbnail(
                    uri = item.uri,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            
            // 选中状态指示器
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// 判断URI是否为视频文件
private fun isVideoUri(uri: Uri): Boolean {
    val path = uri.toString().lowercase()
    return path.contains(".mp4") || path.contains(".avi") || path.contains(".mov") || 
           path.contains(".mkv") || path.contains(".wmv") || path.contains(".flv") ||
           path.contains(".3gp") || path.contains(".webm") || path.contains(".m4v")
}

@Composable
private fun PlaceholderGrid(type: String) {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Text("$type 功能开发中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* -------------------- 底部已选文件Chip -------------------- */
@Composable
private fun SelectedFileChip(uri: Uri) {
    AssistChip(
        onClick = { },
        label = { Text(uri.lastPathSegment ?: "file") },
        shape = RoundedCornerShape(8.dp)
    )
}

/* -------------------- 获取相册全部图片 -------------------- */
private fun getLocalImages(cr: ContentResolver): List<MediaItem> {
    val list = mutableListOf<MediaItem>()
    try {
        val uriExternal = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("image/%")

        cr.query(uriExternal, projection, selection, selectionArgs, "${MediaStore.Images.Media.DATE_TAKEN} DESC")
            ?.use { cursor ->
                val idColumn  = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val mimeColumn= cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idColumn)
                    val mime = cursor.getString(mimeColumn)
                    val uri  = Uri.withAppendedPath(uriExternal, id.toString())
                    list += MediaItem(uri, mime)
                }
            }
    } catch (e: Exception) {
        Log.e("VideoDebug", "getLocalImages 失败", e)
    }
    Log.d("VideoDebug", "getLocalImages 返回 ${list.size} 条")
    return list
}

/* -------------------- 获取相册全部视频 -------------------- */
private fun getLocalVideos(cr: ContentResolver): List<MediaItem> {
    val list = mutableListOf<MediaItem>()
    try {
        val uriExternal = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Video.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("video/%")

        cr.query(uriExternal, projection, selection, selectionArgs, "${MediaStore.Video.Media.DATE_TAKEN} DESC")
            ?.use { cursor ->
                val idColumn  = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val mimeColumn= cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idColumn)
                    val mime = cursor.getString(mimeColumn)
                    val uri  = Uri.withAppendedPath(uriExternal, id.toString())
                    list += MediaItem(uri, mime)
                }
            }
    } catch (e: Exception) {
        Log.e("VideoDebug", "getLocalVideos 失败", e)
    }
    Log.d("VideoDebug", "getLocalVideos 返回 ${list.size} 条")
    return list
}
