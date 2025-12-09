package com.example.nfcbeam

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File

/** * 下载路径管理器 * 管理文件接收的保存位置 */
class DownloadPathManager(private val context: Context) {
    companion object {
        private const val TAG = "DownloadPathManager"
        private const val PREFS_NAME = "download_path_prefs"
        private const val KEY_DOWNLOAD_LOCATION = "download_location"
        private const val KEY_CUSTOM_PATH_URI = "custom_path_uri" // 新增：用于存储自定义路径


        /** * 下载位置枚举 */
        enum class DownloadLocation(val displayName: String, val folderName: String) {
            DOWNLOADS("下载", Environment.DIRECTORY_DOWNLOADS),
            DOCUMENTS("文档", Environment.DIRECTORY_DOCUMENTS),
            PICTURES("图片", Environment.DIRECTORY_PICTURES),
            MOVIES("视频", Environment.DIRECTORY_MOVIES),
            MUSIC("音乐", Environment.DIRECTORY_MUSIC),
            CUSTOM("自定义", "CUSTOM"); // 确保有这个枚举项

            companion object {
                fun fromName(name: String): DownloadLocation {
                    return values().find { it.name == name } ?: DOWNLOADS
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** * 获取当前下载位置 */
    fun getCurrentLocation(): DownloadLocation {
        val locationName = prefs.getString(KEY_DOWNLOAD_LOCATION, DownloadLocation.DOWNLOADS.name)
        return DownloadLocation.fromName(locationName ?: DownloadLocation.DOWNLOADS.name)
    }

    /** * 设置下载路径 */
    fun setDownloadPath(location: DownloadLocation) {
        prefs.edit().putString(KEY_DOWNLOAD_LOCATION, location.name).apply()
        Log.d(TAG, "下载路径已设置为: ${location.displayName}")
    }

    // ============ 新增：自定义路径相关方法 ============

    /** * 设置自定义下载路径 (存储 Tree URI) */
    fun setCustomDownloadPath(uri: Uri) {
        prefs.edit().putString(KEY_CUSTOM_PATH_URI, uri.toString()).apply()
        // 同时将位置切换为 CUSTOM
        setDownloadPath(DownloadLocation.CUSTOM)
    }

    /** * 获取自定义路径的 URI，如果未设置则返回 null */
    fun getCustomDownloadPath(): Uri? {
        val uriString = prefs.getString(KEY_CUSTOM_PATH_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    /** * 获取自定义路径的显示名称，用于 UI 展示 */
    fun getCustomPathDisplayName(): String {
        return getCustomDownloadPath()?.let { uri ->
            // 尝试从 URI 中提取有意义的名称，这里简化处理
            "自定义: ${uri.lastPathSegment ?: "未知目录"}"
        } ?: "未设置自定义目录"
    }

    // ==============================================

    /** * 获取下载目录 */
    fun getDownloadDirectory(): File {
        val location = getCurrentLocation()
        return if (location == DownloadLocation.CUSTOM) {
            // 对于自定义路径，我们不能直接返回 File，因为它是 SAF URI
            // 这里返回一个占位符，实际写入应使用 ContentResolver
            // 但为了兼容旧逻辑，我们返回应用私有目录作为 fallback
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        } else {
            Environment.getExternalStoragePublicDirectory(location.folderName)
        }
    }

    /** * 确保下载目录存在（对于公共目录有效） */
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

    /** * 获取下载目录路径字符串 */
    fun getDownloadPath(): String {
        return getDownloadDirectory().absolutePath
    }
}