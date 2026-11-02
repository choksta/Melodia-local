package com.lin0721.linmusic.feature.player.ui

import com.lin0721.linmusic.core.ui.components.PlayPauseIcon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.ui.components.MelodiaIconButton
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.ui.interaction.pressScale
import com.lin0721.linmusic.core.ui.theme.LocalUiMotion
import com.lin0721.linmusic.core.ui.theme.MelodiaColors
import com.lin0721.linmusic.core.ui.theme.MelodiaPress
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing

// 主控件行：随机/漫游、上一曲、播放暂停、下一曲、循环模式
// FM 上下文下两侧换成「不喜欢」与「换一批」：随机和循环对一条无穷推荐流没有意义
@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    playMode: PlayMode,
    isRoaming: Boolean = false,
    onDisableRoaming: () -> Unit = {},
    isIntelligence: Boolean = false,
    onDisableIntelligence: () -> Unit = {},
    // 播客没有随机与循环，隐藏后用等宽占位保持播放键居中
    showModeButtons: Boolean = true,
    isFm: Boolean = false,
    onDislike: () -> Unit = {},
    // 为 null 时右侧回落成循环按钮，非 FM 调用方不必关心
    onRefreshStream: (() -> Unit)? = null,
    // 已下达播放意图但还没真正出声（弱网缓冲）。没有这个提示，点下去界面一动不动，
    // 用户会以为按钮没响应
    isBuffering: Boolean = false
) {
    val motion = LocalUiMotion.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MelodiaSpacing.lg)
            .padding(top = MelodiaSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showModeButtons) MelodiaIconButton(
            onClick = {
                when {
                    isFm -> onDislike()
                    isRoaming -> onDisableRoaming()
                    isIntelligence -> onDisableIntelligence()
                    else -> onToggleShuffle()
                }
            },
            modifier = Modifier.offset(x = (-10).dp)
        ) {
            Icon(
                imageVector = when {
                    isFm -> Icons.Rounded.Delete
                    isRoaming -> Icons.Rounded.AllInclusive
                    isIntelligence -> Icons.Rounded.AutoAwesome
                    else -> Icons.Default.Shuffle
                },
                contentDescription = if (isFm) "不喜欢，换一首" else null,
                tint = if (!isFm && (isRoaming || isIntelligence || playMode == PlayMode.SHUFFLE)) {
                    MelodiaColors.TextPrimary
                } else {
                    MelodiaColors.TextSecondary
                },
                modifier = Modifier.size(28.dp)
            )
        } else Spacer(Modifier.size(ModeButtonSlot))

        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MelodiaIconButton(onClick = onPlayPrevious, style = MelodiaPress.Transport) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = MelodiaColors.TextPrimary, modifier = Modifier.size(46.dp))
            }
            val playInteraction = remember { MutableInteractionSource() }
            val isPressed by playInteraction.collectIsPressedAsState()
            val buttonAlpha = remember { Animatable(1f) }
            var clickTrigger by remember { mutableIntStateOf(0) }

            LaunchedEffect(isPressed) {
                if (isPressed) {
                    buttonAlpha.animateTo(0.70f, motion.tween(durationMillis = 80))
                } else if (clickTrigger == 0) {
                    buttonAlpha.animateTo(1f, motion.tween(durationMillis = 200, easing = FastOutSlowInEasing))
                }
            }

            LaunchedEffect(clickTrigger) {
                if (clickTrigger > 0) {
                    buttonAlpha.snapTo(0.70f)
                    buttonAlpha.animateTo(1f, motion.tween(durationMillis = 220, easing = FastOutSlowInEasing))
                }
            }

            Box(contentAlignment = Alignment.Center) {
                FloatingActionButton(
                    onClick = {
                        clickTrigger++
                        onTogglePlay()
                    },
                    containerColor = MelodiaColors.TextPrimary,
                    shape = CircleShape,
                    interactionSource = playInteraction,
                    modifier = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            alpha = buttonAlpha.value
                        }
                ) {
                    PlayPauseIcon(isPlaying, MelodiaColors.InverseText, size = 42.dp)
                }
                // 外圈转环而不是替换图标：按钮上的暂停图标已经表示"要播了"，
                // 这里补的是"还在等数据"这一层信息，两者不冲突
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(84.dp),
                        color = MelodiaColors.TextPrimary.copy(alpha = 0.55f),
                        strokeWidth = 2.dp,
                        trackColor = Color.Transparent
                    )
                }
            }
            MelodiaIconButton(onClick = onPlayNext, style = MelodiaPress.Transport) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = MelodiaColors.TextPrimary, modifier = Modifier.size(46.dp))
            }
        }

        when {
            isFm && onRefreshStream != null -> MelodiaIconButton(
                onClick = onRefreshStream,
                modifier = Modifier.offset(x = 10.dp)
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "换一批推荐",
                    tint = MelodiaColors.TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }

            showModeButtons -> MelodiaIconButton(
                onClick = onToggleRepeat,
                modifier = Modifier.offset(x = 10.dp)
            ) {
                Icon(
                    if (playMode == PlayMode.SINGLE_LOOP) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = null,
                    tint = if (playMode == PlayMode.SINGLE_LOOP) MelodiaColors.TextPrimary else MelodiaColors.TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }

            else -> Spacer(Modifier.size(ModeButtonSlot))
        }
    }
}

// 与图标按钮同宽的占位
private val ModeButtonSlot = 40.dp
