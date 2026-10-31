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

    fun open() {
        scope.launch { drawerState.open() }
    }

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
