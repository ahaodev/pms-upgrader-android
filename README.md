# pms-upgrader

PMS 平台配套的 Android 应用内更新库，提供检查更新、下载 APK、MD5 校验、自动安装等完整更新流程，基于 Kotlin Coroutines + StateFlow 实现响应式状态管理。

---

## 功能特性

- **检查更新**：向 PMS 服务端 POST `/access/check` 查询最新版本
- **下载 APK**：通过状态流报告实时进度，失败后可重新下载
- **MD5 校验**：服务端提供 MD5 时，在发布完成状态前校验文件
- **安装 APK**：通过 FileProvider 支持 Android 7.0 及以上
- **响应式状态**：通过 `StateFlow` 暴露 `UpdateState`，方便 Compose / LiveData 集成

---

## 接入方式

### 1. 添加 JitPack 仓库

**`settings.gradle.kts`**
```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. 添加依赖

```kotlin
implementation("com.github.ahaodev:pmsupgrader:版本号")
```

最新版本请查看 [Releases](https://github.com/ahaodev/pmsupgrader/releases)。

### 3. 配置 FileProvider

在应用的 `AndroidManifest.xml` 中声明 FileProvider（安装 APK 所需）：

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

在 `res/xml/file_provider_paths.xml` 中添加下载目录：

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Android 10+ (API 29+): context.externalCacheDir/downloads/ -->
    <external-cache-path name="apk_cache_downloads" path="downloads/" />
    <cache-path name="apk_internal_cache_downloads" path="downloads/" />
    <!-- Android 7-9 (API 24-28): public Downloads/MSC/ -->
    <external-path name="apk_public_downloads" path="Download/MSC/" />
    <!-- Android 7-9 fallback: app external files dir -->
    <external-files-path name="apk_files_downloads" path="Download/downloads/" />
</paths>
```

---

## 使用示例

### 初始化

```kotlin
val upgrader = Upgrader.Builder(context)
    .accessToken("YOUR_ACCESS_TOKEN")   // PMS 平台访问令牌
    .baseUrl("https://your-pms-server.com")
    .project("your-project-name")
    .packageName("com.your.app")
    .build()
```

四个参数均为必填，缺少任意一项 `build()` 将抛出 `IllegalArgumentException`。

### 检查更新

```kotlin
// 在页面处于 STARTED 时收集状态，用于渲染界面。
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        upgrader.updateState.collect { state ->
            renderUpdateState(state)
        }
    }
}

lifecycleScope.launch {
    upgrader.checkUpdate(currentVersionCode = "1.0.0")
}
```

### 一键完整更新流程

```kotlin
lifecycleScope.launch {
    upgrader.performUpdate(
        currentVersion = "1.0.0",
        autoInstall = true        // 下载完成后自动触发安装
    )
}
```

### 手动分步执行

在用户点击“下载”时使用 `UpdateAvailable` 返回的更新信息：

```kotlin
val downloadUrl = response.downloadUrl ?: return
val filePath = upgrader.downloadApk(downloadUrl, "update.apk", response.fileHash)
```

下载地址支持绝对 HTTP(S) URL 和相对于 `baseUrl` 的路径。访问令牌仅发送给与 `baseUrl` 同源的下载请求。下载失败后可使用同一更新信息重试，进度会从头开始。

在用户点击“安装”时处理系统安装器的启动结果：

```kotlin
when (val result = upgrader.requestInstall(filePath)) {
    InstallResult.InstallerOpened -> showMessage("请在系统安装器中确认安装")
    is InstallResult.PermissionRequired -> {
        // 用户点击“打开授权设置”时启动该 Intent，返回后可重试安装。
        showInstallSettingsAction(result.settingsIntent)
    }
    is InstallResult.Failed -> showMessage(result.message)
}
```

`InstallerOpened` 表示系统安装器已打开。应用完成更新仍需用户在系统界面确认。使用 `performUpdate(autoInstall = true)` 时，由该方法触发安装，状态收集只用于渲染界面。需要处理授权或提供安装重试的界面可以使用手动流程。

---

## UpdateState 状态说明

| 状态 | 说明 |
|------|------|
| `Idle` | 初始/重置状态 |
| `Checking` | 正在向服务端查询 |
| `UpdateAvailable(response)` | 有新版本，包含下载地址、changelog 等 |
| `NoUpdateAvailable` | 当前已是最新版本 |
| `Downloading(progress)` | 下载中，携带字节数和百分比 |
| `DownloadCompleted(filePath)` | 下载完成，携带本地文件路径 |
| `Error(message, throwable)` | 任意阶段出错 |

---

## 最低要求

| 项目 | 要求 |
|------|------|
| minSdk | 24（Android 7.0） |
| 编译语言 | Kotlin |
| 序列化 | kotlinx.serialization |

---

## 发布新版本

APK 的发布（上传至 PMS 平台）使用 [pms-releaser](https://github.com/ahaodev/pms-releaser)。

在 GitHub Actions 中推送 `v*` tag 即可触发自动构建并发布 AAR 到 JitPack：

```bash
git tag v1.0.0
git push origin v1.0.0
```

发布 APK 到 PMS 平台示例：

```yaml
- uses: ahaodev/pms-releaser@main
  with:
    file_path: './app-release.apk'
    version: ${{ github.ref_name }}
    project_name: ${{ vars.PROJECT_NAME }}
    package_name: ${{ vars.PACKAGE_NAME }}
    access_token: ${{ secrets.ACCESS_TOKEN }}
    release_url: ${{ secrets.RELEASE_URL }}
```

---

## License

[MIT](LICENSE)
