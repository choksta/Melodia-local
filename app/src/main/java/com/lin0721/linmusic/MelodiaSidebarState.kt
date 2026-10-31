package com.lin0721.linmusic

import android.os.SystemClock
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 侧边栏状态：一层薄包装，内部用 Material3 的 [DrawerState]。
 *
 * 之前是自己拿 AnchoredDraggableState + translationX 手写的抽屉，问题出在
 * 「主内容层平移让出位置」这个模式对命中测试不友好——面板露出来了，点击却仍
 * 被主内容层吃掉；调布局偏移、调 zIndex 都只能碰运气。
 *
 * 换成 ModalNavigationDrawer 之后，面板是独立的 drawerContent 槽，命中关系由
 * 官方实现保证；边缘手势、遮罩点击关闭、开合动画都是内置的。
 */
@Stable
class MelodiaSidebarState(
    private val scope: CoroutineScope,
    val drawerState: DrawerState
) {

    val isOpen: Boolean get() = drawerState.currentValue == DrawerValue.Open

    /**
     * 瞬时到位，不做滑入。
     *
     * 滑入动画的 300ms 里面板位置一直在变，用户看到一点就去点，那个位置此刻还是
     * 遮罩——「打开后不能秒交互」就是这么来的。位置固定之后，看到多少点多少。
     * 过渡感不靠位移，而由两件事承担：面板自身淡入 + 主内容轻微后退（见 MelodiaApp）。
     */
    fun open() {
        scope.launch { drawerState.snapTo(DrawerValue.Open) }
    }

    fun close() {
        scope.launch { drawerState.close() }
    }

    /**
     * 关闭抽屉并立刻执行 [action]。
     *
     * 曾经试过「等关闭动画走完再跳」：`drawerState.close()` 之后调 action。
     * 但 close 的动画会被手势取消（点击的 UP 事件被 anchoredDraggable 当成拖动结束），
     * animateTo 抛 CancellationException，协程结束、action 轮不到执行；
     * 更糟的是状态可能停在不确定的位置，`isOpen` 仍为 true——
     * 之后按返回键会一直去「收抽屉」而不是「返回上一页」，返回就彻底失灵了。
     *
     * 所以改成瞬时关闭：snapTo 是确定的状态变更，不会被打断、也不会留下半开状态；
     * 关闭后再导航，新页面出来时抽屉已经不在了，不存在残留。
     * 打开仍有 Material 原生滑入动画，收起时干脆利落，符合抽屉的一般手感。
     */
    /**
     * 跳转到新页面并收起侧栏。
     *
     * 顺序很关键：**先导航，再收起**。
     *
     * 反过来（先 snapTo 收起再导航）会「闪回主页」：侧栏瞬间消失的那一帧，
     * 主页完整裸露出来，而新页面才刚开始从透明淡入（260ms）——
     * 用户先看到一整屏主页，再看到新页面浮现，像是点了两次。
     *
     * 现在的顺序让两件事重叠：新页面开始入场的同时侧栏滑走。
     * 侧栏滑出的 150ms 里新页面已经淡入过半，切换是连续的。
     */
    fun closeThen(action: () -> Unit) {
        // 告诉页面层：这次切换别淡入。
        // 侧栏滑出本身就是过渡，新页面再从透明淡进来会多出「主页空档」——
        // 侧栏已经走了、新页面还没显形，那一瞬就是用户说的「闪回主页」。
        lastSidebarNavAt = SystemClock.uptimeMillis()
        action()
        scope.launch { drawerState.close() }
    }

    companion object {
        /**
         * 最近一次侧栏跳转的时间戳（uptimeMillis）。
         *
         * 用它而不是一次性布尔标志：AnimatedContent 的 transitionSpec 在某些情况下
         * 会被重复求值，布尔标志可能被提前消耗掉，导致这次切换又淡入了。
         * 用「300ms 窗口内发生的切换算侧栏触发」就不会有这个问题。
         */
        var lastSidebarNavAt: Long = 0L
    }
}

@Composable
fun rememberMelodiaSidebarState(): MelodiaSidebarState {
    val scope = rememberCoroutineScope()
    val drawerState = remember {
        // 用数组持有自身引用，供 confirmStateChange 判断「是否正滑到一半」
        val holder = arrayOfNulls<DrawerState>(1)
        DrawerState(DrawerValue.Closed) { target ->
            // 只在「正从关闭滑向打开」这段里忽略关闭请求。
            //
            // 面板滑入的这 300ms 里，用户看到一点就会去点，而那个位置此刻还是遮罩；
            // 若照单全收，抽屉会「刚打开又自己关上」，表现就是「点不了还消失了」。
            //
            // 条件必须收得很窄：一旦写成「currentValue != targetValue」，
            // 打开动画刚结束的临界点上仍然成立，此时连程序发起的关闭都会被拒绝，
            // 抽屉状态就卡在 Open —— 紧接着返回键会一直去「收抽屉」，返回彻底失灵。
            val opening = holder[0]?.let {
                it.targetValue == DrawerValue.Open && it.currentValue == DrawerValue.Closed
            } ?: false
            !(target == DrawerValue.Closed && opening)
        }.also { holder[0] = it }
    }
    return remember(scope, drawerState) { MelodiaSidebarState(scope, drawerState) }
}
