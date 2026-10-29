package com.lin0721.linmusic.core.update.data

import com.lin0721.linmusic.core.update.domain.UpdateInfo
import com.lin0721.linmusic.core.update.domain.VersionCodeResolver
import kotlin.coroutines.cancellation.CancellationException

/**
 * 读取 GitHub Releases 并挑出「比当前安装版本更新」的那一条
 *
 * [owner] / [repo] 默认是上游官方仓库；本地 fork 会在 DI 里换成自己的仓库，
 * 这样才能装到自己打出来的包。
 */
class UpdateRepository(
    private val api: GithubReleaseApi,
    private val owner: String = GITHUB_OWNER,
    private val repo: String = GITHUB_REPO
) {

    // 按渠道过滤后取 versionCode 最大的一条，仅当高于当前安装版本才视为"有更新"
    suspend fun fetchLatestUpdate(currentVersionCode: Int, allowPrerelease: Boolean): Result<UpdateInfo?> {
        return try {
            val releases = api.listReleases(owner, repo)
            val candidate = releases
                .asSequence()
                .filterNot { it.draft }
                .filter { allowPrerelease || !it.prerelease }
                .mapNotNull { dto ->
                    val versionCode = resolveVersionCode(dto) ?: return@mapNotNull null
                    val apkAsset = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                    UpdateInfo(
                        versionName = dto.tagName,
                        versionCode = versionCode,
                        isPrerelease = dto.prerelease,
                        changelog = dto.body.orEmpty(),
                        releasePageUrl = dto.htmlUrl.ifBlank { releasesUrl + "/tag/" + dto.tagName },
                        apkUrl = apkAsset?.browserDownloadUrl?.ifBlank { null },
                        apkSizeBytes = apkAsset?.size ?: 0L,
                        apkFileName = apkAsset?.name?.ifBlank { null }
                    )
                }
                .maxByOrNull { it.versionCode }

            val hasUpdate = candidate != null && candidate.versionCode > currentVersionCode
            Result.success(if (hasUpdate) candidate else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * tag → versionCode 的三级解析
     *
     * 1. 优先读 release 正文里的显式标记（发布脚本会把打出来的 APK 真实 versionCode 写进去）。
     *    fork 里的 tag 形如 `v2.1.0-beta.1-local1`，而实际 versionCode 是 20512 —— 这类本地编号
     *    并不遵循上游 CI 的推导公式，只能靠标记。
     * 2. 其次从 APK 资源文件名里找 `-<versionCode>.apk`
     * 3. 最后才回退到上游 tag 推导，但**跳过带 -local 的 tag**：
     *    上游公式会把 2.1.0-beta.1 算成 2010001，比本地 205xx 大两个数量级，
     *    若参与比较会把「自己这个版本」误判成新版本，让用户点下载装回同一个包。
     *    宁可漏报，不可误报。
     */
    private fun resolveVersionCode(dto: GithubReleaseDto): Int? {
        extractVersionCodeMarker(dto.body)?.let { return it }

        dto.assets.firstNotNullOfOrNull { extractVersionCodeMarker(it.name) }?.let { return it }

        if (dto.tagName.contains("-local", ignoreCase = true)) return null
        return VersionCodeResolver.resolve(dto.tagName)
    }

    // 匹配 versionCode=20512 / versionCode: 20512 / -20512.apk 这几种写法
    private fun extractVersionCodeMarker(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        MARKER_REGEX.find(text)?.let { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        APK_NAME_REGEX.find(text)?.let { match ->
            match.groupValues.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private val releasesUrl: String get() = "https://github.com/$owner/$repo/releases"

    companion object {
        private const val GITHUB_OWNER = "rinchao0721"
        private const val GITHUB_REPO = "Melodia"

        // 大小写不敏感，允许 = 或 : 分隔，数字至少 4 位（本地编号已到 20512）
        private val MARKER_REGEX =
            Regex("""versionCode\s*[:=]\s*(\d{4,9})""", RegexOption.IGNORE_CASE)

        // 资源名里的 -20512.apk
        private val APK_NAME_REGEX = Regex("""-(\d{4,9})\.apk""", RegexOption.IGNORE_CASE)
    }
}
