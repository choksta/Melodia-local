package com.lin0721.linmusic.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.interaction.pressable
import com.lin0721.linmusic.core.ui.theme.MelodiaColors
import com.lin0721.linmusic.core.ui.theme.MelodiaPress
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing
import com.lin0721.linmusic.core.ui.theme.PillRadius

// 受控的横向筛选药丸组：Library 的分类过滤、Home 的内容筛选共用
@Composable
fun FilterChipsRow(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // 首次组合时把选中项滚进可视区。列表长（如音效预设 12 档）而选中项在很后面时，
    // 不滚的话打开页面只看得到前几项，根本不知道自己当前选的是哪个
    autoScrollToSelected: Boolean = false
) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (autoScrollToSelected && selectedIndex in items.indices) {
            // 留一项上下文在前面，避免选中项紧贴左边缘看不出前后
            listState.scrollToItem((selectedIndex - 1).coerceAtLeast(0))
        }
    }
    LazyRow(
        modifier = modifier.padding(top = MelodiaSpacing.sm),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items.indices.toList(), key = { it }) { index ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .pressable(MelodiaPress.Pill) { onSelected(index) }
                    .clip(RoundedCornerShape(PillRadius))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MelodiaColors.Overlay.copy(alpha = 0.1f))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = items[index],
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MelodiaColors.PillContent,
                    fontSize = 14.sp
                )
            }
        }
    }
}
