package com.ahao.app.ui

import android.content.ActivityNotFoundException

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ahao.upgrader.InstallResult
import com.ahao.upgrader.UpdateCheckResponse
import com.ahao.upgrader.UpdateState
import com.ahao.upgrader.Upgrader
import kotlinx.coroutines.launch

@Composable
internal fun UpgraderTestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("https://ppr.ahaodev.com") }
    var accessToken by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("Test") }
    var packageName by remember { mutableStateOf("debug") }
    var currentVersion by remember { mutableStateOf("1.0") }
    var upgrader by remember { mutableStateOf<Upgrader?>(null) }
    var inputError by remember { mutableStateOf<UpdateState.Error?>(null) }
    var retryDownload by remember { mutableStateOf<UpdateCheckResponse?>(null) }
    var installResult by remember { mutableStateOf<InstallResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    val activeUpgrader = upgrader
    val updateState = key(activeUpgrader) {
        activeUpgrader?.updateState?.collectAsState()?.value ?: UpdateState.Idle
    }
    val state = inputError ?: updateState

    fun download(response: UpdateCheckResponse) {
        val downloader = activeUpgrader ?: return
        val url = response.downloadUrl ?: return
        if (busy) return
        retryDownload = response
        installResult = null
        busy = true
        scope.launch {
            try {
                if (downloader.downloadApk(url, "update.apk", response.fileHash) != null) {
                    retryDownload = null
                }
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("升级测试工具", style = MaterialTheme.typography.headlineSmall)
        UpdateField("服务器地址", baseUrl, { baseUrl = it }, !busy)
        UpdateField(
            "访问令牌", accessToken, { accessToken = it }, !busy,
            visualTransformation = PasswordVisualTransformation(),
        )
        UpdateField("项目名称", project, { project = it }, !busy)
        UpdateField("应用包名", packageName, { packageName = it }, !busy)
        UpdateField("当前版本", currentVersion, { currentVersion = it }, !busy)

        Button(
            onClick = {
                if (busy) return@Button
                retryDownload = null
                installResult = null
                val nextUpgrader = try {
                    Upgrader.Builder(context)
                        .baseUrl(baseUrl.trim())
                        .accessToken(accessToken.trim())
                        .project(project.trim())
                        .packageName(packageName.trim())
                        .build()
                } catch (e: IllegalArgumentException) {
                    inputError = UpdateState.Error(e.message ?: "更新配置无效")
                    return@Button
                }

                inputError = null
                upgrader = nextUpgrader
                busy = true
                scope.launch {
                    try {
                        nextUpgrader.checkUpdate(currentVersion.trim())
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && currentVersion.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("检查更新")
        }

        UpdateStatus(
            state = state,
            busy = busy,
            onDownload = ::download,
            onInstall = { path -> installResult = activeUpgrader?.requestInstall(path) },
        )
        val retry = retryDownload
        if (state is UpdateState.Error && retry != null) {
            Button(
                onClick = { download(retry) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重试下载")
            }
        }
        when (val result = installResult) {
            is InstallResult.PermissionRequired -> {
                Text("请允许此应用安装未知来源的应用，返回后再点安装。")
                Button(
                    onClick = {
                        try {
                            context.startActivity(result.settingsIntent)
                        } catch (_: ActivityNotFoundException) {
                            installResult = InstallResult.Failed("无法打开授权设置，请在系统设置中手动授权")
                        } catch (_: SecurityException) {
                            installResult = InstallResult.Failed("设备限制了安装授权，请检查系统设置")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开授权设置")
                }
            }
            is InstallResult.Failed -> Text(result.message, color = MaterialTheme.colorScheme.error)
            InstallResult.InstallerOpened -> Text("请在系统安装器中确认安装。")
            null -> Unit
        }
    }
}

@Composable
private fun UpdateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun UpdateStatus(
    state: UpdateState,
    busy: Boolean,
    onDownload: (UpdateCheckResponse) -> Unit,
    onInstall: (String) -> Unit,
) {
    when (state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> {
            Text("正在检查更新…")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        UpdateState.NoUpdateAvailable -> Text("已是最新版本")
        is UpdateState.UpdateAvailable -> {
            val response = state.response
            Text("发现新版本 ${response.latestVersion.orEmpty()}")
            response.fileSize?.let { Text("文件大小 ${it} B") }
            response.fileHash?.let { Text("MD5 ${it}") }
            response.downloadUrl?.let { Text(it) }
            if (response.changelog.isNotBlank()) Text(response.changelog)
            Button(
                onClick = { onDownload(response) },
                enabled = !busy && !response.downloadUrl.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("下载更新")
            }
        }
        is UpdateState.Downloading -> {
            val percent = state.progress.progress
            if (percent < 0) {
                Text("正在下载更新…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Text("正在下载更新 ${percent}%")
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is UpdateState.DownloadCompleted -> {
            Text("下载完成")
            Button(
                onClick = { onInstall(state.filePath) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("安装更新")
            }
        }
        is UpdateState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
    }
}
