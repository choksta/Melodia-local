package com.lin0721.linmusic.feature.player.ui

import androidx.compose.foundation.border
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.ui.graphics.Color
import com.lin0721.linmusic.core.ui.components.PlayPauseIcon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.lin0721.linmusic.core.plugin.ControlVariant
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
import com.lin0721.linmusic.core.ui.theme.outlinedControls
import com.lin0721.linmusic.core.ui.theme.styledButtonShape

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
            val outlined = outlinedControls
            val transportOutline = if (outlined) Modifier.border(1.dp, MelodiaColors.Outline, CircleShape) else Modifier
            MelodiaIconButton(onClick = onPlayPrevious, style = MelodiaPress.Transport, modifier = transportOutline) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = MelodiaColors.TextPrimary, modifier = Modifier.size(46.dp))
            }
            PlayPauseFab(isPlaying, onTogglePlay, isBuffering = isBuffering)
            MelodiaIconButton(onClick = onPlayNext, style = MelodiaPress.Transport, modifier = transportOutline) {
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

// 大播放键：按下变淡，松手或点击后恢复
@Composable
private fun PlayPauseFab(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    scale: Float = 1f,
    outlined: Boolean = outlinedControls,
    isBuffering: Boolean = false
) {
    val motion = LocalUiMotion.current
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
            containerColor = if (outlined) Color.Transparent else MelodiaColors.TextPrimary,
            shape = styledButtonShape(CircleShape),
            elevation = if (outlined) FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp) else FloatingActionButtonDefaults.elevation(),
            interactionSource = playInteraction,
            modifier = Modifier
                .size(72.dp * scale)
                .then(if (outlined) Modifier.border(2.dp, MelodiaColors.TextPrimary, styledButtonShape(CircleShape)) else Modifier)
                .graphicsLayer {
                    alpha = buttonAlpha.value
                }
        ) {
            PlayPauseIcon(isPlaying, if (outlined) MelodiaColors.TextPrimary else MelodiaColors.InverseText, size = 42.dp * scale)
        }
        // 外圈转环而不是替换图标：按钮上的暂停图标已经表示"要播了"，
        // 这里补的是"还在等数据"这一层信息，两者不冲突
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(84.dp * scale),
                color = MelodiaColors.TextPrimary.copy(alpha = 0.55f),
                strokeWidth = 2.dp,
                trackColor = Color.Transparent
            )
        }
    }
}

// 插件布局用的控制行：按给定按钮与顺序排布，播客不显示随机与循环
@Composable
fun PlaybackControlRow(
    buttons: List<String>,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    playMode: PlayMode,
    showModeButtons: Boolean,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    variant: ControlVariant = ControlVariant.DEFAULT
) {
    val outlined = variant == ControlVariant.OUTLINED || (variant == ControlVariant.DEFAULT && outlinedControls)
    val transportOutline = if (outlined) Modifier.border(1.dp, MelodiaColors.Outline, CircleShape) else Modifier
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp * scale),
        verticalAlignment = Alignment.CenterVertically
    ) {
        buttons.forEach { button ->
            when (button) {
                "shuffle" -> if (showModeButtons) MelodiaIconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "随机播放",
                        tint = if (playMode == PlayMode.SHUFFLE) MelodiaColors.TextPrimary else MelodiaColors.TextSecondary,
                        modifier = Modifier.size(28.dp * scale)
                    )
                }
                "previous" -> MelodiaIconButton(onClick = onPlayPrevious, style = MelodiaPress.Transport, modifier = transportOutline) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一首", tint = MelodiaColors.TextPrimary, modifier = Modifier.size(46.dp * scale))
                }
                // plain：播放键与切歌键同样是无底色图标
                "playPause" -> if (variant == ControlVariant.PLAIN) {
                    MelodiaIconButton(onClick = onTogglePlay, style = MelodiaPress.Transport) {
                        PlayPauseIcon(isPlaying, MelodiaColors.TextPrimary, size = 46.dp * scale)
                    }
                } else {
                    PlayPauseFab(isPlaying, onTogglePlay, scale, outlined)
                }
                "next" -> MelodiaIconButton(onClick = onPlayNext, style = MelodiaPress.Transport, modifier = transportOutline) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "下一首", tint = MelodiaColors.TextPrimary, modifier = Modifier.size(46.dp * scale))
                }
                "repeat" -> if (showModeButtons) MelodiaIconButton(onClick = onToggleRepeat) {
                    Icon(
                        if (playMode == PlayMode.SINGLE_LOOP) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "循环模式",
                        tint = if (playMode == PlayMode.SINGLE_LOOP) MelodiaColors.TextPrimary else MelodiaColors.TextSecondary,
                        modifier = Modifier.size(28.dp * scale)
                    )
                }
                // 列表循环 → 单曲循环 → 随机 → 列表循环
                "playMode" -> if (showModeButtons) MelodiaIconButton(
                    onClick = if (playMode == PlayMode.LIST_LOOP) onToggleRepeat else onToggleShuffle
                ) {
                    Icon(
                        when (playMode) {
                            PlayMode.LIST_LOOP -> Icons.Default.Repeat
                            PlayMode.SINGLE_LOOP -> Icons.Default.RepeatOne
                            PlayMode.SHUFFLE -> Icons.Default.Shuffle
                        },
                        contentDescription = when (playMode) {
                            PlayMode.LIST_LOOP -> "列表循环"
                            PlayMode.SINGLE_LOOP -> "单曲循环"
                            PlayMode.SHUFFLE -> "随机播放"
                        },
                        tint = MelodiaColors.TextPrimary,
                        modifier = Modifier.size(28.dp * scale)
                    )
                }
            }
        }
    }
}

// 与图标按钮同宽的占位
private val ModeButtonSlot = 40.dp
