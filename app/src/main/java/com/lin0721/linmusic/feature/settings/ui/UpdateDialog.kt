package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.ui.components.MelodiaTextButton
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.core.ui.theme.MelodiaColors
import com.lin0721.linmusic.core.update.UpdateInstaller
import com.lin0721.linmusic.core.update.domain.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val TAG = "UpdateDialog"

// 弹窗内部的安装流程状态
private sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val percent: Int, val downloaded: Long, val total: Long) : InstallState
    data class Ready(val file: File) : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * 全局更新弹窗
 *
 * 有 APK 直链时走应用内下载 - 调起系统安装器；没有直链才退回浏览器打开发布页。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onIgnore: () -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var state by remember(info.versionName) { mutableStateOf<InstallState>(InstallState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // 弹窗消失就停掉下载，避免在后台白跑流量
    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    fun install(file: File) {
        if (!UpdateInstaller.canInstallPackages(context)) {
            UpdateInstaller.openInstallPermissionSettings(context)
            ToastManager.showToast("请允许「安装未知应用」后重试")
            return
        }
        if (UpdateInstaller.install(context, file)) {
            onHide()
        } else {
            ToastManager.showToast("未找到系统安装器，请到 GitHub 发布页手动下载")
            state = InstallState.Failed("无法调起安装器")
        }
    }

    fun startDownload() {
        val url = info.apkUrl ?: return
        if (!UpdateInstaller.canInstallPackages(context)) {
            UpdateInstaller.openInstallPermissionSettings(context)
            ToastManager.showToast("请允许「安装未知应用」后重试")
            return
        }
        val fileName = info.apkFileName ?: "Melodia-${info.versionName}.apk"
        // total 先给 0：还没拿到响应头，界面据此显示「正在连接下载源…」，
        // 拿到真实长度后 DownloadProgressRow 会自动切成确定进度条
        state = InstallState.Downloading(0, 0L, 0L)
        downloadJob = scope.launch {
            try {
                val file = UpdateInstaller.download(context, url, fileName) { p ->
                    state = InstallState.Downloading(p.percent, p.downloadedBytes, p.totalBytes)
                }
                state = InstallState.Ready(file)
                install(file)
            } catch (e: CancellationException) {
                state = InstallState.Idle
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "下载安装包失败", e)
                state = InstallState.Failed(e.message ?: "下载失败")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onHide,
        title = {
            val channelSuffix = if (info.isPrerelease) "（测试版）" else ""
            Text(
                text = "发现新版本 ${info.versionName}$channelSuffix",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = info.changelog.ifBlank { "暂无更新说明" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                )
                Spacer(modifier = Modifier.height(12.dp))
                when (val s = state) {
                    is InstallState.Downloading -> DownloadProgressRow(s)
                    is InstallState.Failed -> Column {
                        Text(
                            text = s.message,
                            color = MelodiaColors.AccentText,
                            fontSize = 12.sp
                        )
                        // 应用内下载全失败时得有出路：一直点「重试」大概率还是同样的结果，
                        // 能退到浏览器下载，用户才不至于卡在这个弹窗里
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "改用浏览器下载",
                            color = MelodiaColors.AccentText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                runCatching { uriHandler.openUri(info.releasePageUrl) }
                                    .onSuccess { onHide() }
                                    .onFailure { e ->
                                        AppLogger.e(TAG, "打开发布页失败", e)
                                        ToastManager.showToast("无法打开浏览器，请手动访问 GitHub 发布页")
                                    }
                            }
                        )
                    }
                    is InstallState.Ready -> Text(
                        text = "已下载完成，正在调起安装…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    InstallState.Idle -> if (info.apkSizeBytes > 0) {
                        Text(
                            text = "安装包 ${formatSize(info.apkSizeBytes)}，下载后可直接安装",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            val text: String
            val onClick: () -> Unit
            when {
                !info.canInstallInApp -> {
                    text = "前往下载"
                    onClick = {
                        runCatching { uriHandler.openUri(info.releasePageUrl) }
                            .onSuccess { onHide() }
                            .onFailure { e ->
                                AppLogger.e(TAG, "打开发布页失败", e)
                                ToastManager.showToast("无法打开浏览器，请手动访问 GitHub 发布页")
                            }
                    }
                }
                state is InstallState.Downloading -> {
                    text = "取消"
                    onClick = {
                        downloadJob?.cancel()
                        downloadJob = null
                        state = InstallState.Idle
                    }
                }
                state is InstallState.Ready -> {
                    text = "安装"
                    onClick = { (state as? InstallState.Ready)?.let { install(it.file) } }
                }
                else -> {
                    text = if (state is InstallState.Failed) "重试" else "立即更新"
                    onClick = { startDownload() }
                }
            }
            MelodiaTextButton(onClick = onClick) {
                Text(text, color = MelodiaColors.AccentText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                MelodiaTextButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    onIgnore()
                }) {
                    Text("忽略此版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(4.dp))
                MelodiaTextButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    onDismiss()
                }) {
                    Text("稍后", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun DownloadProgressRow(state: InstallState.Downloading) {
    // total 未知（还没拿到响应头、正在探测下载源，或服务器用 chunked 编码）时
    // 画成不确定进度条：此时 percent 恒为 0，确定进度条会一直显示 0% 像卡死了
    val unknownTotal = state.total <= 0L
    val label = when {
        !unknownTotal -> "正在下载 ${state.percent}%"
        state.downloaded <= 0L -> "正在连接下载源…"
        else -> "正在下载 ${formatSize(state.downloaded)}"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (unknownTotal) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MelodiaColors.AccentText
            )
        } else {
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MelodiaColors.AccentText
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!unknownTotal) {
                Text(
                    text = "${formatSize(state.downloaded)} / ${formatSize(state.total)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.US, "%.1f MB", mb) else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
