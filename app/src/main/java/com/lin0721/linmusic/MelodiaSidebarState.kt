package com.lin0721.linmusic

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
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
 * 官方实现保证；边缘手势、遮罩点击关闭、开合动画都是内置的。这里只保留
 * open / close / isOpen 这几个调用点需要的接口。
 */
@Stable
class MelodiaSidebarState(
    private val scope: CoroutineScope,
    val drawerState: DrawerState
) {

    val isOpen: Boolean get() = drawerState.currentValue == DrawerValue.Open

    /**
     * 瞬时到位，不滑入。
     *
     * 抽屉默认是 300ms 的滑入动画，这期间面板还在屏幕外或半途——用户看到一点
     * 就想去点，但那个位置此刻还是遮罩，点击命中的是遮罩，抽屉反而被关掉。
     * 实测复现：打开后立刻点「最近播放」回到首页，等 1.5 秒再点才进去。
     *
     * snapTo 让面板第一帧就落在最终位置，看到多少就能点多少；
     * 过渡感由面板自身的淡入补上（见 MelodiaApp 的 drawerContent）。
     */
    fun open() {
        scope.launch { drawerState.snapTo(DrawerValue.Open) }
    }

    /** 关闭保留正常动画：收起来的时候不需要「能点」 */
    fun close() {
        scope.launch { drawerState.close() }
    }
}

@Composable
fun rememberMelodiaSidebarState(): MelodiaSidebarState {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    return remember(scope, drawerState) { MelodiaSidebarState(scope, drawerState) }
}
