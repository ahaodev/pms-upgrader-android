package com.ahao.upgrader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object Utils {
    val TAG = Utils::class.java.simpleName
    /**
     * 安装APK文件
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            require(apkFile.isFile && apkFile.length() > 0L) { "APK 文件不存在或为空" }
            launchInstaller(context, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "安装 APK 失败", e)
        }
    }

    internal fun launchInstaller(context: Context, apkFile: File) {
        val authority = "${context.packageName}${Constants.FILEPROVIDER_AUTHORITY_SUFFIX}"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, Constants.APK_MIME_TYPE)
        }
        context.startActivity(intent)
    }

    /**
     * 计算文件MD5值
     */
    fun calculateMD5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance(Constants.MD5_ALGORITHM)
            file.inputStream().use { inputStream ->
                val buffer = ByteArray(Constants.BUFFER_SIZE)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { Constants.HEX_FORMAT.format(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取应用下载目录 - 根据Android版本选择合适的存储策略
     */
    fun getDownloadDir(context: Context): File {
        val internalDownloads = File(context.cacheDir, Constants.DOWNLOADS_DIR_NAME)
        val preferredDirectories = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOfNotNull(context.externalCacheDir?.let { File(it, Constants.DOWNLOADS_DIR_NAME) })
        } else {
            listOfNotNull(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MSC"),
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?.let { File(it, Constants.DOWNLOADS_DIR_NAME) },
            )
        }

        return (preferredDirectories + internalDownloads).firstOrNull { dir ->
            (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
        } ?: throw IOException("无法创建 APK 下载目录")
    }

    /**
     * 删除旧的APK文件
     */
    fun cleanOldApkFiles(context: Context) {
        try {
            val downloadDir = getDownloadDir(context)
            downloadDir.listFiles { _, name -> name.endsWith(Constants.APK_FILE_EXTENSION) }
                ?.forEach { file ->
                    file.delete()
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 格式化文件大小
     */
    fun formatFileSize(sizeInBytes: Long): String {
        val kb = sizeInBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$sizeInBytes B"
        }
    }
}