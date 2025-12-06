package com.example.nfcbeam

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * 下载路径管理器
 * 管理文件接收的保存位置
 */
class DownloadPathManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DownloadPathManager"
        private const val PREFS_NAME = "download_path_prefs"
        private const val KEY_DOWNLOAD_LOCATION = "download_location"
        
        /**
         * 下载位置枚举
         */
        enum class DownloadLocation(val displayName: String, val folderName: String) {
            DOWNLOADS("下载", Environment.DIRECTORY_DOWNLOADS),
            DOCUMENTS("文档", Environment.DIRECTORY_DOCUMENTS),
            PICTURES("图片", Environment.DIRECTORY_PICTURES),
            MOVIES("视频", Environment.DIRECTORY_MOVIES),
            MUSIC("音乐", Environment.DIRECTORY_MUSIC);
            
            companion object {
                fun fromName(name: String): DownloadLocation {
                    return values().find { it.name == name } ?: DOWNLOADS
                }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 获取当前下载位置
     */
    fun getCurrentLocation(): DownloadLocation {
        val locationName = prefs.getString(KEY_DOWNLOAD_LOCATION, DownloadLocation.DOWNLOADS.name)
        return DownloadLocation.fromName(locationName ?: DownloadLocation.DOWNLOADS.name)
    }
    
    /**
     * 设置下载路径
     */
    fun setDownloadPath(location: DownloadLocation) {
        prefs.edit().putString(KEY_DOWNLOAD_LOCATION, location.name).apply()
        Log.d(TAG, "下载路径已设置为: ${location.displayName}")
    }
    
    /**
     * 获取下载目录
     */
    fun getDownloadDirectory(): File {
        val location = getCurrentLocation()
        return Environment.getExternalStoragePublicDirectory(location.folderName)
    }
    
    /**
     * 确保下载目录存在
     */
    fun ensureDownloadDirectoryExists(): File {
        val downloadDir = getDownloadDirectory()
        if (!downloadDir.exists()) {
            val created = downloadDir.mkdirs()
            if (created) {
                Log.d(TAG, "创建下载目录: ${downloadDir.absolutePath}")
            } else {
                Log.e(TAG, "无法创建下载目录: ${downloadDir.absolutePath}")
            }
        }
        return downloadDir
    }
    
    /**
     * 获取下载目录路径字符串
     */
    fun getDownloadPath(): String {
        return getDownloadDirectory().absolutePath
    }
}