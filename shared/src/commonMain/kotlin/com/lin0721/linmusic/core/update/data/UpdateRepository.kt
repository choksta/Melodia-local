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
                        changelog = stripMarkers(dto.body),
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

    // 去掉正文里的 HTML 注释：发布脚本用它写 versionCode 标记，不该显示给用户；
    // 再把 Markdown 语法摊平成纯文本——弹窗只有一个 Text，不认 # 和 **，
    // 原样显示出来就是「## 🎵 播放页细节」这种一眼像出错的排版
    private fun stripMarkers(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return markdownToPlainText(body.replace(HTML_COMMENT_REGEX, "")).trim()
    }

    /**
     * 把 GitHub Release 正文常用的 Markdown 收敛成可读的纯文本。
     *
     * 只处理本仓库发布日志实际会用到的那几种写法，不追求完整的 Markdown 解析：
     * 标题降级成加粗感的独立行、列表转成「· 」、去掉粗体/斜体/代码符号、链接只留文字。
     * 保留原有换行，段落结构不会丢。
     */
    private fun markdownToPlainText(source: String): String {
        val out = StringBuilder()
        for (rawLine in source.lines()) {
            var line = rawLine
            when {
                // 分隔线：整行 --- / *** / ___，直接当空行用
                SEPARATOR_REGEX.matches(line.trim()) -> line = ""
                // 表格的对齐行 |---|---| 只承担排版作用，丢掉
                TABLE_ALIGN_REGEX.matches(line.trim()) -> line = ""
                // 表格数据行：| a | b | → a：b，两列以上用「· 」分隔
                TABLE_ROW_REGEX.matches(line.trim()) -> {
                    val cells = line.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    line = when (cells.size) {
                        0 -> ""
                        1 -> cells[0]
                        2 -> "${cells[0]}：${cells[1]}"
                        else -> cells[0] + "：" + cells.drop(1).joinToString(" · ")
                    }
                }
                // 标题 # ~ ######：去掉井号，靠「· 」前缀保留层级感
                HEADING_REGEX.containsMatchIn(line) -> {
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.drop(level).trim()
                    line = if (level >= 3) "· $text" else text
                }
                // 无序列表：- / * / + 开头换成中点
                BULLET_REGEX.containsMatchIn(line) -> {
                    line = line.replaceFirst(BULLET_REGEX, "· ")
                }
                // 引用：去掉 > 前缀
                QUOTE_REGEX.containsMatchIn(line) -> {
                    line = line.replaceFirst(QUOTE_REGEX, "")
                }
            }
            // 行内的格式符号统一去掉；链接 [文字](url) 只留文字
            line = INLINE_LINK_REGEX.replace(line) { it.groupValues[1] }
            line = BOLD_ITALIC_REGEX.replace(line, "")
            line = INLINE_CODE_REGEX.replace(line) { it.groupValues[1] }
            out.append(line).append('\n')
        }
        // 连续空行压成一个，避免正文里夹着大片空白
        return out.toString().replace(MULTI_BLANK_REGEX, "\n\n")
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

        // 默认仓库（上游官方）的发布页地址；实例内部会用构造时的 owner/repo 拼自己的
        val RELEASES_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
        val REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

        // 大小写不敏感，允许 = 或 : 分隔，数字至少 4 位（本地编号已到 20512）
        private val MARKER_REGEX =
            Regex("""versionCode\s*[:=]\s*(\d{4,9})""", RegexOption.IGNORE_CASE)

        // 资源名里的 -20512.apk
        private val APK_NAME_REGEX = Regex("""-(\d{4,9})\.apk""", RegexOption.IGNORE_CASE)

        // HTML 注释块，跨行
        private val HTML_COMMENT_REGEX = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

        // 发布日志里的 Markdown 语法；只覆盖标题、列表、引用、分隔线与行内格式
        private val SEPARATOR_REGEX = Regex("""^[-*_]{3,}$""")
        private val TABLE_ALIGN_REGEX = Regex("""^\|?[\s:-]*-[\s|:-]*$""")
        private val TABLE_ROW_REGEX = Regex("""^\|.*\|$""")
        private val HEADING_REGEX = Regex("""^#{1,6}\s""")
        private val BULLET_REGEX = Regex("""^\s*[-*+]\s+""")
        private val QUOTE_REGEX = Regex("""^\s*>\s?""")
        private val INLINE_LINK_REGEX = Regex("""\[([^\]]*)\]\([^)]*\)""")
        private val BOLD_ITALIC_REGEX = Regex("""(\*\*|__|\*|_|~~)""")
        private val INLINE_CODE_REGEX = Regex("""`([^`]*)`""")
        private val MULTI_BLANK_REGEX = Regex("""\n{3,}""")
    }
}
