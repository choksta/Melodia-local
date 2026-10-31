package com.lin0721.linmusic.core.log

import android.content.Context
import com.lin0721.linmusic.core.AppEnvironment
import com.lin0721.linmusic.core.preferences.PreferencesStores
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.preferences.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

// 初始化发生在 Application.onCreate，此时主线程上的任何阻塞都会直接计入冷启动。
// 因此先按运行环境写入等价于原默认值的级别，再异步读取用户保存的级别覆盖，
// 避免在主线程用 runBlocking 等 DataStore 首次落盘读取。
fun AppLogger.init(context: Context) {
    val settings = SettingsPreferences(PreferencesStores.get(context, PreferencesStores.SETTINGS))
    val fallbackLevel = if (AppEnvironment.isDebug) "DEBUG" else "WARN"
    init(File(context.cacheDir, "logs")) { fallbackLevel }

    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        val saved = runCatching { settings.logLevel.first() }.getOrNull() ?: return@launch
        val level = runCatching { AppLogger.LogLevel.valueOf(saved) }.getOrNull()
        if (level != null) setLevel(level)
    }
}
