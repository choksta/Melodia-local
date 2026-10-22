package com.lin0721.linmusic.core.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lin0721.linmusic.core.ui.interaction.MelodiaPressIndication

private val DarkColorScheme = darkColorScheme(
    primary = NeteaseRed,
    onPrimary = Color.White,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    error = NeteaseRed,
    onError = Color.White
)

// 浅色方案：主色沿用品牌红，正文近黑、次级文字中灰；
// surface 比 background 略深一档，让卡片与弹层在纯白底上仍有层次
private val LightColorScheme = lightColorScheme(
    primary = NeteaseRed,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    error = NeteaseRed,
    onError = Color.White
)

// 当前是否深色主题。TextGray / SurfaceDark 这类颜色常量据此在两种取值间切换，
// 组件沿用原有常量名即可自动跟随主题，无需逐处判断
val LocalIsDarkTheme = staticCompositionLocalOf { true }

// Melodia 应用的全局 Material3 主题配置
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MelodiaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            shapes = MelodiaShapes
        ) {
            CompositionLocalProvider(
                LocalIndication provides MelodiaPressIndication.Default,
                LocalRippleConfiguration provides null,
                content = content
            )
        }
    }
}
