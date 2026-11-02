package com.lin0721.linmusic.feature.fm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.feature.fm.data.FmSong
import com.lin0721.linmusic.core.ui.components.SecondaryScreenScaffold
import com.lin0721.linmusic.core.ui.theme.MelodiaColors
import org.koin.androidx.compose.koinViewModel

/**
 * 私人 FM。
 *
 * 与传统列表页的区别：这里没有「列表」，只有当前这一首。
 * 用户不需要选歌，只需要决定「留下 / 丢掉 / 再换一首」——
 * 所以界面只保留封面、标题和一个三按钮控制条。
 */
@Composable
fun FmScreen(
    viewModel: FmViewModel = koinViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val isPlaying by viewModel.playerManager.isPlaying.collectAsStateWithLifecycle()

    // 进入页面自动起播；已经在播 FM 时 enter() 内部会直接返回，不打断
    LaunchedEffect(Unit) { viewModel.enter() }

    SecondaryScreenScaffold(
        title = "私人 FM",
        onBack = onBack,
        actions = {
            if (state.isFmActive) {
                IconButton(onClick = { viewModel.startNewStream() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "换一批",
                        tint = MelodiaColors.TextSecondary
                    )
                }
            }
        }
    ) {
        val showing = if (state.isFmActive) current else null
        when {
            state.error != null && showing == null -> FmMessage(
                message = state.error ?: "私人 FM 加载失败",
                actionLabel = "重试",
                onAction = { viewModel.startNewStream() }
            )

            state.isLoading && showing == null -> FmLoading()

            showing == null -> FmMessage(
                message = "从一个可能喜欢的歌开始",
                actionLabel = "开始漫游",
                onAction = { viewModel.startNewStream() }
            )

            else -> FmNowPlaying(
                item = showing,
                isPlaying = isPlaying,
                onToggle = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onDislike = viewModel::dislike
            )
        }
    }
}

@Composable
private fun ColumnScope.FmNowPlaying(
    item: FmSong,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onDislike: () -> Unit
) {
    Spacer(modifier = Modifier.weight(1f))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 44.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(MelodiaColors.Surface)
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }

    Spacer(modifier = Modifier.height(30.dp))

    Text(
        text = item.name,
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
        color = MelodiaColors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp)
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = item.artistText,
        fontSize = 14.sp,
        color = MelodiaColors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp)
    )

    Spacer(modifier = Modifier.weight(1f))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FmSecondaryButton(
            icon = Icons.Rounded.Delete,
            contentDescription = "不喜欢，换一首",
            onClick = onDislike
        )
        FmPlayButton(isPlaying = isPlaying, onClick = onToggle)
        FmSecondaryButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "下一首",
            onClick = onNext
        )
    }

    // 底部迷你播放条是浮层，不留出这段高度会把三个按钮压住
    Spacer(modifier = Modifier.height(LocalBottomOverlayInset.current + 24.dp))
}

@Composable
private fun FmPlayButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MelodiaColors.AccentText),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MelodiaColors.WindowBackground,
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

@Composable
private fun FmSecondaryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: Dp = 56.dp,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MelodiaColors.TextSecondary,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun ColumnScope.FmLoading() {
    Spacer(modifier = Modifier.weight(1f))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MelodiaColors.AccentText)
    }
    Spacer(modifier = Modifier.weight(1f))
}

@Composable
private fun ColumnScope.FmMessage(
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Spacer(modifier = Modifier.weight(1f))
    Text(
        text = message,
        fontSize = 14.sp,
        color = MelodiaColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onAction) {
            Text(text = actionLabel, color = MelodiaColors.AccentText, fontSize = 15.sp)
        }
    }
    Spacer(modifier = Modifier.weight(1f))
}
