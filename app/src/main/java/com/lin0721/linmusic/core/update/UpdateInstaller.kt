package com.lin0721.linmusic.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lin0721.linmusic.core.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

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

    // 单源最多观察这么久，期间下载量低于阈值就判定为“慢源”，换下一个
    private const val SLOW_CHECK_AFTER_MS = 6_000L
    private const val SLOW_MIN_BYTES = 400L * 1024

    // 国内可用的 GitHub 加速镜像。这里的顺序只是兜底，真正用哪个由 rankSources 探测决定：
    // 镜像会挂（ghfast.top 在 2026-11 已完全无响应），硬编码的「优先级」过不了多久就会过期，
    // 届时下载会先卡满一个连接超时才换源，用户看到的就是进度条一直不动。
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.org/",
        "https://ghproxy.net/"
    )

    // 探测超时：每个源并发发一个 1 字节 Range 请求，这么久没回应就当作不可用
    private const val PROBE_TIMEOUT_MS = 4_000L

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
        updateDir(context).listFiles()?.forEach { if (it.name != fileName && it.name != tmp.name) it.delete() }

        // 先告诉 UI「还没拿到响应头」（total=0 时界面画不确定进度条）。
        // 不这么做的话，探测各源的这几秒里进度条会停在 0%，看起来就是卡住了
        onProgress(Progress(0L, 0L))

        var lastError: Exception? = null
        for (candidate in rankSources(url)) {
            try {
                if (!attemptDownload(candidate, tmp, onProgress)) {
                    AppLogger.w(TAG, "下载源不可用或过慢，切换下一个：${candidate.take(60)}")
                    continue
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) error("下载完成但重命名失败")
                onProgress(Progress(target.length(), target.length()))
                return@withContext target
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                AppLogger.w(TAG, "下载源失败：${candidate.take(60)}", e)
            }
        }
        throw lastError ?: IllegalStateException("所有下载源均不可用")
    }

    /**
     * 单源下载尝试，返回 false 表示这个源不行（HTTP 错误或速率过低），调用方换下一个源
     *
     * 半成品保留在 .part 里，下一个源用 Range 续传，不会从零开始
     */
    private suspend fun attemptDownload(url: String, tmp: File, onProgress: (Progress) -> Unit): Boolean {
        var startOffset = if (tmp.exists()) tmp.length() else 0L
        var response = requestWithRange(url, startOffset)

        // 服务器不认 Range（回落 200）就从头来
        if (startOffset > 0 && response.code != 206) {
            response.close()
            tmp.delete()
            startOffset = 0L
            response = requestWithRange(url, 0L)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                // 416 说明 .part 已经等于完整长度，交给上层重命名校验
                if (resp.code == 416) return true
                return false
            }
            val body = resp.body ?: return false
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) startOffset + contentLength else 0L

            var downloaded = startOffset
            var lastEmit = downloaded
            val beginBytes = downloaded
            val beginAt = System.currentTimeMillis()

            body.byteStream().use { input ->
                FileOutputStream(tmp, startOffset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (System.currentTimeMillis() - beginAt > SLOW_CHECK_AFTER_MS &&
                            downloaded - beginBytes < SLOW_MIN_BYTES
                        ) {
                            // 速率过低：先落盘再退出，换下一个源续传
                            output.flush()
                            return false
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        // 每 256 KB 回调一次，避免刷爆 UI
                        if (downloaded - lastEmit >= BUFFER_SIZE * 4 || (total > 0 && downloaded >= total)) {
                            lastEmit = downloaded
                            onProgress(Progress(downloaded, total))
                        }
                    }
                    output.flush()
                }
            }

            if (total > 0 && downloaded < total) {
                // 连接提前断开，保留 .part 让下一个源续传
                return false
            }
            onProgress(Progress(downloaded, total.takeIf { it > 0 } ?: downloaded))
            return true
        }
    }

    private fun requestWithRange(url: String, offset: Long): Response {
        val builder = Request.Builder().url(url).header("Accept", "application/octet-stream")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        return client.newCall(builder.build()).execute()
    }

    /**
     * 下载源候选：GitHub 官方 CDN 在国内常见几十 KB/s，先走加速镜像，最后回落到官方直连
     *
     * 换源不降低安全性：APK 有签名，镜像返回的包被改过系统会拒绝安装。
     */
    private fun candidateUrls(url: String): List<String> {
        if (!url.contains("github.com/") && !url.contains("githubusercontent.com/")) return listOf(url)
        return MIRROR_PREFIXES.map { it + url } + url
    }

    /**
     * 并发探测各源，按响应快慢排序。
     *
     * 镜像挂掉时最怕的不是失败，而是「排在前面却要等满连接超时」：原来 ghfast.top 无响应，
     * 下载会先静默等 20 秒才换下一个源，界面上就是进度条一动不动。
     *
     * 这里并发发出 1 字节的 Range 请求，**只要有一个源通了就立即开工**，不等最慢的那个；
     * 一个都没通才等到 [PROBE_TIMEOUT_MS]。探测没回的源仍保留在列表末尾——
     * 可能只是探测请求被拦，实际下载是通的。
     */
    private suspend fun rankSources(url: String): List<String> {
        val candidates = candidateUrls(url)
        if (candidates.size <= 1) return candidates

        val results = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        coroutineScope {
            val jobs = candidates.map { candidate ->
                launch { probe(candidate)?.let { results.add(candidate to it) } }
            }
            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            while (results.isEmpty() && System.currentTimeMillis() < deadline) {
                delay(40)
            }
            jobs.forEach { it.cancel() }
        }

        if (results.isEmpty()) return candidates
        val ranked = results.sortedBy { it.second }.map { it.first }
        return ranked + candidates.filterNot { it in ranked }
    }

    /** 返回响应耗时（毫秒），不可用返回 null */
    private suspend fun probe(url: String): Long? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            val begin = System.currentTimeMillis()
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-0")
                        .build()
                ).execute().use { response ->
                    if (response.isSuccessful) System.currentTimeMillis() - begin else null
                }
            }.getOrNull()
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
