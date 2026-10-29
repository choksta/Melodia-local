package com.lin0721.linmusic.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 应用内更新：把 release 里的 APK 下到 cache，再交给系统安装器
 *
 * 不落 DownloadManager 是为了拿到确定的进度与可控的取消；
 * 文件放在 cacheDir/updates（已由 file_paths.xml 暴露给 FileProvider）。
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val DIR_NAME = "updates"
    private const val BUFFER_SIZE = 64 * 1024

    // 下载走独立的客户端：读超时放宽，避免大包在慢网下被 30s 掐断
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class Progress(val downloadedBytes: Long, val totalBytes: Long) {
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    fun updateDir(context: Context): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    /** 清理上次遗留的安装包，避免缓存堆积 */
    fun clearDownloaded(context: Context) {
        runCatching {
            updateDir(context).listFiles()?.forEach { it.delete() }
        }
    }

    /**
     * 下载 APK，[onProgress] 在 IO 线程回调，调用方自己切主线程
     *
     * 支持协程取消：取消时删掉半成品文件并抛出 CancellationException
     */
    suspend fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val target = File(updateDir(context), fileName)
        val tmp = File(updateDir(context), "$fileName.part")
        // 同版本重复下载没有意义，已有完整文件直接复用
        if (target.exists() && target.length() > 0) return@withContext target
        // 只保留当前要装的这一个包，避免历史版本堆在缓存里
        updateDir(context).listFiles()?.forEach { if (it.name != fileName) it.delete() }

        val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                val body = response.body ?: error("下载失败：响应体为空")
                val total = body.contentLength().takeIf { it > 0 } ?: 0L
                var downloaded = 0L

                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastEmit = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            // 每 256 KB 或进度跨 1% 才回调，避免刷爆 UI
                            if (downloaded - lastEmit >= BUFFER_SIZE * 4 || downloaded == total) {
                                lastEmit = downloaded
                                onProgress(Progress(downloaded, total))
                            }
                        }
                        output.flush()
                    }
                }

                if (total > 0 && downloaded != total) error("下载不完整：$downloaded/$total")
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) error("下载完成但重命名失败")
                onProgress(Progress(target.length(), target.length().takeIf { total <= 0 } ?: total))
                target
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
    }

    /** Android 8.0+ 需要用户在系统设置里允许本应用安装未知来源应用 */
    fun canInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            AppLogger.e(TAG, "打开未知来源设置失败", e)
            // 部分 ROM 没有该页面，退回到应用详情页
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
    }

    /** 调起系统安装器；返回 false 表示没找到可处理该 Intent 的组件 */
    fun install(context: Context, apk: File): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure { e ->
            AppLogger.e(TAG, "调起安装器失败", e)
        }.getOrDefault(false)
    }
}
