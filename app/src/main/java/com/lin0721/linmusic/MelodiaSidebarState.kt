package com.lin0721.linmusic

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

    fun open() {
        scope.launch { drawerState.open() }
    }

    fun close() {
        scope.launch { drawerState.close() }
    }

    /**
     * 先等抽屉完全收起，再执行 [action]。
     *
     * 点菜单项时若「启动关闭动画」和「跳转页面」同时发生，新页面会先渲染，
     * 而抽屉还要滑出 300ms——那半秒里侧栏压在新页面上，就是画面残留。
     * 串成「收完再跳」，既是标准抽屉的节奏，也不会有残影。
     */
    fun closeThen(action: () -> Unit) {
        scope.launch {
            drawerState.close()
            action()
        }
    }
}

@Composable
fun rememberMelodiaSidebarState(): MelodiaSidebarState {
    val scope = rememberCoroutineScope()
    val drawerState = remember {
        // 用数组持有自身引用，供 confirmStateChange 判断「是否正滑到一半」
        val holder = arrayOfNulls<DrawerState>(1)
        DrawerState(DrawerValue.Closed) { target ->
            // 抽屉还在滑动时忽略关闭请求。
            //
            // 面板滑入的这 300ms 里，用户看到一点就会去点，而那个位置此刻还是遮罩；
            // 若照单全收，抽屉会「刚打开又自己关上」，表现就是「点不了还消失了」。
            // 忽略掉之后，点早了只是没反应，抽屉继续滑入，到位就能正常点。
            val sliding = holder[0]?.let { it.currentValue != it.targetValue } ?: false
            !(target == DrawerValue.Closed && sliding)
        }.also { holder[0] = it }
    }
    return remember(scope, drawerState) { MelodiaSidebarState(scope, drawerState) }
}
