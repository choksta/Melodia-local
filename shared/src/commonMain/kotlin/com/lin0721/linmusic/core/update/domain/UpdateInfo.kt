package com.lin0721.linmusic.core.update.domain

// 一次可用更新的领域模型
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val isPrerelease: Boolean,
    val changelog: String,
    val releasePageUrl: String,
    // 发布页里附带的 APK 直链；为空表示该 release 没上传安装包，只能跳浏览器
    val apkUrl: String? = null,
    val apkSizeBytes: Long = 0L,
    val apkFileName: String? = null
) {
    // 能否走应用内下载安装
    val canInstallInApp: Boolean get() = !apkUrl.isNullOrBlank()
}
