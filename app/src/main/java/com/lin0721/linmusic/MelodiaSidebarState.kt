package com.lin0721.linmusic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 侧边栏开合状态。
 *
 * ## 为什么不用 Material3 的 DrawerState
 *
 * 用过两轮，问题都出在「面板位移」：
 * - `open()` 是滑入动画，300ms 里面板位置一直在变。用户看到面板露头就去点，
 *   那一瞬点击落在遮罩上，抽屉反而被关掉——表现就是「点了没反应还消失了」；
 * - `snapTo()` 能秒点，但「啪」一下出现，没有过渡；
 * - 想两头兼顾就得在 `confirmStateChange` 里拦关闭请求，一拦就容易把状态卡在
 *   Open，接着返回键只会去「收抽屉」，返回失灵。
 *
 * 现在的思路和设置页子菜单切换一致（见 SettingsScreen 的 AnimatedContent）：
 * **新内容原地淡入、只让旧内容位移**。侧栏位置永远钉死在 [0, SidebarWidth]，
 * 由 alpha 承担浮现，主内容向右滑开承担「推开」的观感。
 *
 * 位置不动，视觉位置就等于命中区域，任何时刻点都准——这是秒响应的唯一解。
 */
@Stable
class MelodiaSidebarState(private val scope: CoroutineScope) {

    /** 0 = 完全关闭，1 = 完全展开。只驱动透明度与主内容位移，不驱动侧栏自身位置。 */
    private val anim = Animatable(0f)

    val progress: Float get() = anim.value

    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
        animateTo(1f)
    }

    fun close() {
        isOpen = false
        animateTo(0f)
    }

    /**
     * 收起并立即执行 [action]（通常是页面跳转）。
     *
     * 用 [Animatable.snapTo] 瞬间归零，而不是等 200ms 淡出：否则新页面已经渲染出来，
     * 侧栏还在慢慢消失，那 200ms 就是用户看到的画面残留。
     * 也不采用「等关完再跳」——那会把跳转本身拖慢半拍，点菜单像没反应。
     */
    fun closeThen(action: () -> Unit) {
        isOpen = false
        scope.launch {
            runCatching { anim.snapTo(0f) }
            action()
        }
    }

    private fun animateTo(target: Float) {
        scope.launch {
            try {
                anim.animateTo(target, tween(200, easing = FastOutSlowInEasing))
            } catch (_: CancellationException) {
                // 快速连开连关时前一段动画会被新的打断，属正常行为
            }
        }
    }
}

@Composable
fun rememberMelodiaSidebarState(): MelodiaSidebarState {
    val scope = rememberCoroutineScope()
    return remember(scope) { MelodiaSidebarState(scope) }
}
