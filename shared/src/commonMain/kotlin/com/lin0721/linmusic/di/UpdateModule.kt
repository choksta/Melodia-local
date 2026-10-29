package com.lin0721.linmusic.di

import com.lin0721.linmusic.core.AppEnvironment
import com.lin0721.linmusic.core.update.UpdateChecker
import com.lin0721.linmusic.core.update.data.GithubReleaseApi
import com.lin0721.linmusic.core.update.data.UpdateRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

private const val GITHUB_BASE_URL = "https://api.github.com/"
private const val GITHUB_CLIENT = "github"

/**
 * 软件更新检查（GitHub Releases）依赖注入模块
 *
 * GitHub API 与网易业务网络隔离，不经过加密拦截器；currentVersionCode 为 null 表示开发构建
 *
 * [owner] / [repo] 传自定义仓库时（本地 fork 场景），会同时放开预发布过滤：
 * 本地打出来的包一律以 prerelease 发布，不放开就永远查不到更新。
 */
fun updateModule(
    currentVersionCode: Int?,
    owner: String? = null,
    repo: String? = null
): Module = module {

    val isCustomRepo = !owner.isNullOrBlank() && !repo.isNullOrBlank()

    single(named(GITHUB_CLIENT)) {
        OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (AppEnvironment.isDebug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
                }
            )
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    single(named(GITHUB_CLIENT)) {
        val json: Json = get()
        Retrofit.Builder()
            .baseUrl(GITHUB_BASE_URL)
            .client(get(named(GITHUB_CLIENT)))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    single<GithubReleaseApi> { get<Retrofit>(named(GITHUB_CLIENT)).create(GithubReleaseApi::class.java) }

    single {
        if (isCustomRepo) UpdateRepository(get(), owner!!, repo!!) else UpdateRepository(get())
    }
    single {
        UpdateChecker(
            updateRepository = get(),
            settingsPreferences = get(),
            currentVersionCode = currentVersionCode,
            alwaysAllowPrerelease = isCustomRepo
        )
    }
}
