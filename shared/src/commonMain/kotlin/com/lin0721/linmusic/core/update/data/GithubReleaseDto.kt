package com.lin0721.linmusic.core.update.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GitHub Releases API 响应体，字段与 GitHub 官方文档保持一致
@Serializable
data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GithubReleaseAssetDto> = emptyList()
)

@Serializable
data class GithubReleaseAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    // 字节数，用于展示下载体积与做进度百分比兜底
    val size: Long = 0L
)
