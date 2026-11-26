package com.ahao.upgrader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.awaitResponse
import java.io.File
import java.io.FileOutputStream

class Upgrader private constructor(
    private val context: Context,
    private val accessToken: String,
    private val baseUrl: String,
    private val project: String,
    private val packageName: String,
) {
    private val updateApi = Service.createUpdateApi(baseUrl, accessToken)
    private val serverUrl = baseUrl.toHttpUrl()
    private val okHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val sameOrigin = url.scheme == serverUrl.scheme &&
                url.host == serverUrl.host && url.port == serverUrl.port
            val builder = request.newBuilder().removeHeader("x-access-token")
            if (sameOrigin) builder.header("x-access-token", accessToken)
            chain.proceed(builder.build())
        }
        .build()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /**
     * 检查更新
     */
    suspend fun checkUpdate(
        currentVersionCode: String,
        clientInfo: String = Constants.DEFAULT_CLIENT_INFO,
    ) {
        _updateState.value = UpdateState.Checking

        try {
            val request = UpdateCheckRequest(
                currentVersion = currentVersionCode,
                clientInfo = clientInfo,
                project = project,
                packageName = packageName,
            )

            val response = updateApi.checkUpdate(request).awaitResponse()
            val body = response.body()
            val update = body?.data
            _updateState.value = when {
                !response.isSuccessful -> {
                    response.errorBody()?.close()
                    UpdateState.Error("更新检查失败（HTTP ${response.code()}）")
                }
                body == null -> UpdateState.Error("更新检查返回空响应")
                !body.success() -> UpdateState.Error(
                    "更新检查失败（${body.code}）" +
                        (body.msg.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
                )
                update == null -> UpdateState.Error("更新检查返回空数据")
                !update.hasUpdate -> UpdateState.NoUpdateAvailable
                update.downloadUrl.isNullOrBlank() ||
                    serverUrl.resolve(update.downloadUrl) == null ->
                    UpdateState.Error("发现新版本，但下载地址缺失或无效")
                else -> UpdateState.UpdateAvailable(update)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("更新检查异常: ${e.message}", e)
        }
    }

    /**
     * 下载APK文件
     */
    suspend fun downloadApk(
        downloadUrl: String,
        fileName: String = Constants.DEFAULT_APK_FILENAME,
    ): String? = downloadApk(downloadUrl, fileName, expectedMd5 = null)

    /** Downloads the APK and checks its MD5 when supplied before emitting DownloadCompleted. */
    suspend fun downloadApk(
        downloadUrl: String,
        fileName: String,
        expectedMd5: String?,
    ): String? = withContext(Dispatchers.IO) {
        var incompleteFile: File? = null
        try {
            require(fileName.isNotBlank() && File(fileName).name == fileName &&
                fileName != "." && fileName != "..") { "APK 文件名无效" }
            val url = downloadUrl.takeIf { it.isNotBlank() }?.let(serverUrl::resolve)
            if (url == null) {
                _updateState.value = UpdateState.Error("下载地址无效")
                return@withContext null
            }

            val directory = Utils.getDownloadDir(context)
            val apkFile = File(directory, fileName)
            val request = Request.Builder().url(url).build()
            _updateState.value = UpdateState.Downloading(DownloadProgress(0, -1, -1))
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _updateState.value = UpdateState.Error("下载失败: ${response.code}")
                    return@withContext null
                }

                val body = response.body
                if (body == null) {
                    _updateState.value = UpdateState.Error("下载内容为空")
                    return@withContext null
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val tempFile = File.createTempFile("pms-", ".part", directory)
                incompleteFile = tempFile
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(Constants.BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            val percent = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).toInt()
                            } else {
                                -1
                            }
                            _updateState.value = UpdateState.Downloading(
                                DownloadProgress(downloadedBytes, totalBytes, percent)
                            )
                        }
                    }
                }
            }

            val tempFile = checkNotNull(incompleteFile)
            if (!verifyApkFile(tempFile.absolutePath, expectedMd5)) {
                _updateState.value = UpdateState.Error("APK文件校验失败")
                return@withContext null
            }
            ensureActive()
            if (!tempFile.renameTo(apkFile)) {
                _updateState.value = UpdateState.Error("无法保存已下载的 APK")
                return@withContext null
            }
            incompleteFile = null
            val filePath = apkFile.absolutePath
            _updateState.value = UpdateState.DownloadCompleted(filePath)
            filePath
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _updateState.value = UpdateState.Error("下载异常: ${e.message}", e)
            null
        } finally {
            incompleteFile?.delete()
        }
    }

    /**
     * 验证下载的APK文件
     */
    fun verifyApkFile(filePath: String, expectedMd5: String?): Boolean {
        val file = File(filePath)
        if (!file.isFile || file.length() == 0L) return false
        if (expectedMd5.isNullOrBlank()) return true

        val actualMd5 = Utils.calculateMD5(file)
        return actualMd5?.equals(expectedMd5, ignoreCase = true) == true
    }

    /**
     * 安装APK
     */
    fun installApk(filePath: String) {
        when (val result = requestInstall(filePath)) {
            InstallResult.InstallerOpened -> Unit
            is InstallResult.PermissionRequired ->
                _updateState.value = UpdateState.Error("请在系统设置中允许此应用安装未知来源的应用")
            is InstallResult.Failed ->
                _updateState.value = UpdateState.Error(result.message, result.throwable)
        }
    }

    /** Requests the system installer and returns the next action to the caller. */
    fun requestInstall(filePath: String): InstallResult {
        val file = File(filePath)
        if (!file.isFile || file.length() == 0L) {
            return InstallResult.Failed("APK 文件不存在或为空")
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                InstallResult.PermissionRequired(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                Utils.launchInstaller(context, file)
                InstallResult.InstallerOpened
            }
        } catch (e: Exception) {
            InstallResult.Failed("无法打开系统安装器：${e.localizedMessage.orEmpty()}", e)
        }
    }

    /**
     * 执行完整的更新流程
     */
    suspend fun performUpdate(
        currentVersion: String,
        clientInfo: String = Constants.DEFAULT_CLIENT_INFO,
        autoInstall: Boolean = true,
    ) {
        checkUpdate(currentVersion, clientInfo)

        val currentState = _updateState.value
        if (currentState !is UpdateState.UpdateAvailable) return

        val updateResponse = currentState.response
        val downloadUrl = updateResponse.downloadUrl

        if (downloadUrl.isNullOrEmpty()) {
            _updateState.value = UpdateState.Error("下载链接为空")
            return
        }

        val fileName = "${packageName}_${updateResponse.latestVersion}_update.apk"
        val filePath = downloadApk(downloadUrl, fileName, updateResponse.fileHash) ?: return

        if (autoInstall) {
            installApk(filePath)
        }
    }

    /**
     * 重置更新状态
     */
    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    class Builder(private val context: Context) {
        private var accessToken: String = ""
        private var baseUrl: String = ""
        private var project: String = ""
        private var packageName: String = ""

        fun accessToken(token: String) = apply { this.accessToken = token }
        fun baseUrl(url: String) = apply { this.baseUrl = url }
        fun project(project: String) = apply { this.project = project }
        fun packageName(pkg: String) = apply { this.packageName = pkg }

        fun build(): Upgrader {
            require(accessToken.isNotEmpty()) { "accessToken must be provided" }
            require(baseUrl.isNotEmpty()) { "baseUrl must be provided" }
            require(project.isNotEmpty()) { "project must be provided" }
            require(packageName.isNotEmpty()) { "packageName must be provided" }
            return Upgrader(context, accessToken, baseUrl, project, packageName)
        }
    }
}