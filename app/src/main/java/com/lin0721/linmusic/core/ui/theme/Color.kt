package com.lin0721.linmusic.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos

// 平滑抗色阶断层的垂直渐变
// 采用余弦缓动曲线采样，首尾斜率平滑过渡；透明渐变保留原色相避免发灰
fun Brush.Companion.smoothVerticalGradient(
    from: Color,
    to: Color,
    startY: Float = 0f,
    endY: Float = Float.POSITIVE_INFINITY,
    fadeEndFraction: Float = 1f,
    steps: Int = 24
): Brush {
    val effectiveTo = if (to == Color.Transparent) from.copy(alpha = 0f) else to
    val effectiveFrom = if (from == Color.Transparent) effectiveTo.copy(alpha = 0f) else from
    val clampedFadeEnd = fadeEndFraction.coerceIn(0f, 1f)

    val stops = Array(if (clampedFadeEnd < 1f) steps + 1 else steps) { i ->
        if (i < steps) {
            val t = i.toFloat() / (steps - 1)
            val factor = ((1.0 - cos(t * PI)) / 2.0).toFloat()
            val color = lerp(effectiveFrom, effectiveTo, factor)
            (t * clampedFadeEnd) to color
        } else {
            1f to effectiveTo
        }
    }

    return verticalGradient(
        colorStops = stops,
        startY = startY,
        endY = endY
    )
}

// HSV 明度按比例偏移，供取色/背景渲染各处按需现算深浅变体，不落地成持久字段。
// 用比例缩放而不是加减法再 coerceIn 夹断：暗色/灰阶封面明度本就 ≤ 0.35，
// 加减法一夹断就是绝对纯黑，色相全丢；比例缩放无论原明度多低都只是"更暗"，不会砸成死黑
fun Color.lighten(amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    hsv[2] = (hsv[2] + (1f - hsv[2]) * amount).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

fun Color.darken(amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    hsv[2] = (hsv[2] * (1f - amount)).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// HSV 饱和度按比例往纯色方向推；原色饱和度为 0（灰阶/黑白）时色相无意义，直接跳过，
// 否则会凭空编出一个色相方向的颜色
fun Color.saturate(amount: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    if (hsv[1] <= 0f) return this
    hsv[1] = (hsv[1] + (1f - hsv[1]) * amount).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// 只给有色 base 提饱和度：中性 base 只带 NEUTRAL_BASE_CHROMA 的轻微色调，提饱和度会放大成明显偏色；
// 两个色度档位之间线性过渡，切歌渐变的中间色不跳变
fun Color.saturateIfChromatic(amount: Float): Color {
    val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
    val weight = ((chroma - NEUTRAL_BASE_CHROMA) / (MIN_BASE_CHROMA - NEUTRAL_BASE_CHROMA)).coerceIn(0f, 1f)
    return saturate(amount * weight)
}

// ======================= 明暗两套基础调色板 =======================
// 这里只放纯色值，供 Theme.kt 构造 ColorScheme 使用（顶层 val 不能是 @Composable）。
// 组件侧请继续用下方与旧名同名的常量：它们已改为按主题返回两套取值之一，
// 因此历史代码无需改动即可跟随明暗切换。

val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF282828)
val DarkSurfaceVariant = Color(0xFF3E3E3E)
val DarkTextSecondary = Color(0xFFB3B3B3)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkBackgroundBlack = Color(0xFF0A0A0A)
val DarkGradientStart = Color(0xFF3A1515)
val DarkCoverPlaceholder = Color(0xFF2C2C2C)

val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF4F4F7)
val LightSurfaceVariant = Color(0xFFE7E7EC)
val LightTextSecondary = Color(0xFF5C5C66)
val LightOnSurface = Color(0xFF121216)
val LightGradientStart = Color(0xFFFBEDED)
val LightCoverPlaceholder = Color(0xFFECECEF)

// 旧名兼容层：按当前主题取对应值。必须用于 @Composable 上下文，
// 非 Composable 位置（remember 块、drawBehind、顶层 val）请直接用 Dark*/Light* 具名色值
val BackgroundDark: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkBackground else LightBackground

val SurfaceDark: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkSurface else LightSurface

val SurfaceLight: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkSurfaceVariant else LightSurfaceVariant

val TextGray: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkTextSecondary else LightTextSecondary

val BackgroundBlack: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkBackgroundBlack else LightBackground

val GradientStart: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkGradientStart else LightGradientStart

val CoverPlaceholderDark: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) DarkCoverPlaceholder else LightCoverPlaceholder

// 底栏选中项的药丸指示器底色
val NavPillSelected: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) Color(0xFF383A4A) else Color(0xFFE8EAF3)

// 筛选药丸未选中态底色。深色下取白 10% 叠在顶栏渐变上的等效不透明色，浅色下取红调浅灰
val FilterPillInactive: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) Color(0xFF332B2B) else Color(0xFFF1E9E9)

// 全局 Toast 底色：两种主题下都做成深色小胶囊，保证白字可读
val ToastBackground = Color(0xFF2E2E2E)

// 叠在表面上的低透明度遮罩（卡片底、分割线、按压反馈）。
// 深色主题下用白，浅色主题下必须换成黑，否则白底上的白色遮罩完全不可见
val SurfaceOverlay: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) Color.White else Color.Black

val NeteaseRed = Color(0xFFC20C0C)

// 网页登录容器底色，与网页自身背景一致以避免键盘弹出时闪屏
val WebLoginBackground = Color(0xFFF5F5F7)

// 封面取色未命中缓存时的兜底背景色，迷你播放器与全屏播放器共用以保证配色一致。
// 播放页保持沉浸深色（封面取色本身偏暗），因此两种主题下都取深色
val FallbackBase = Color(0xFF333333)

// 定时关闭中的剩余时间告警色
val TimerWarningRed = Color(0xFFFF5252)

// 音乐库卡片的装饰渐变：紫罗兰 → 紫 → 粉
val LibraryVioletGradient = listOf(
    Color(0xFF6366F1),
    Color(0xFFA855F7),
    Color(0xFFEC4899)
)

// 音乐库卡片的装饰渐变：蓝 → 翠绿
val LibraryBlueGreenGradient = listOf(
    Color(0xFF3B82F6),
    Color(0xFF10B981)
)

// 已下载标识翠绿色
val DownloadedGreen = Color(0xFF10B981)

// 下载失败标识红色，比品牌红更亮，深色背景上作文字仍可读
val DownloadFailedRed = Color(0xFFFF6B6B)

// SVIP 标识金色
val SvipGold = Color(0xFFD4A017)

// 首页功能入口的策展渐变。心动模式、音乐漫游这类功能本就没有对应封面，
// 用固定色块表达
val EntryHotGradient = listOf(Color(0xFFF08C1A), Color(0xFF9C4A05))
val EntryHeartGradient = listOf(Color(0xFFA33FC4), Color(0xFF4B1268))
val EntryRadarGradient = listOf(Color(0xFF2F7FD6), Color(0xFF123F70))
val EntryRoamingGradient = listOf(Color(0xFF3FAE5C), Color(0xFF175C2C))
