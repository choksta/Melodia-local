package com.lin0721.linmusic.feature.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.ui.components.MelodiaIconButton
import com.lin0721.linmusic.core.ui.interaction.copyOnLongClick
import com.lin0721.linmusic.core.ui.theme.InfoCardRadius
import com.lin0721.linmusic.core.ui.theme.LocalUiMotion
import com.lin0721.linmusic.core.ui.theme.MelodiaColors
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing
import com.lin0721.linmusic.feature.podcast.ui.PodcastPlayerState

// 歌名/歌手切歌过渡：进比出略长，新内容落定感更稳；两者叠在一起就是 220ms 的交叉
private const val SongInfoTextEnterMs = 220
private const val SongInfoTextExitMs = 160

// 封面下方的歌名歌手行，右侧带收藏按钮
@Composable
fun SongInfo(
    title: String,
    artist: String,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    // 播客节目：♡ 直接订阅所属电台
    podcast: PodcastPlayerState = PodcastPlayerState(),
    onToggleSubscribe: () -> Unit = {}
) {
    val motion = LocalUiMotion.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MelodiaSpacing.lg)
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 歌名和歌手整体做一次过渡：封面是左右推入的，文字如果直接换，会抢在封面滑完之前
        // 就变成下一首，视觉上像"封面还在滑、字已经换了"。两行绑在同一个 targetState 上，
        // 保证它们绝对同步，不会一个先到一个后到
        AnimatedContent(
            targetState = title to artist,
            transitionSpec = {
                (fadeIn(motion.tween(SongInfoTextEnterMs)) +
                        slideInVertically(motion.tween(SongInfoTextEnterMs)) { height -> height / 4 })
                    .togetherWith(
                        fadeOut(motion.tween(SongInfoTextExitMs)) +
                                slideOutVertically(motion.tween(SongInfoTextExitMs)) { height -> -height / 4 }
                    )
            },
            label = "song_info_text",
            modifier = Modifier
                .weight(1f)
                .padding(end = MelodiaSpacing.md)
        ) { (songTitle, songArtist) ->
            Column {
                Text(
                    text = songTitle,
                    color = MelodiaColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    modifier = Modifier
                        .basicMarquee()
                        .copyOnLongClick(songTitle, "歌名", onClick = { onAlbumClick?.invoke() })
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = songArtist,
                    color = MelodiaColors.TextPrimary.copy(alpha = 0.75f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.copyOnLongClick(songArtist, "歌手", onClick = { onArtistClick?.invoke() })
                )
            }
        }
        if (podcast.isPodcast) {
            if (podcast.canSubscribe) {
                MelodiaIconButton(onClick = onToggleSubscribe, modifier = Modifier.offset(x = 10.dp)) {
                    Icon(
                        if (podcast.subscribed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (podcast.subscribed) "取消订阅电台" else "订阅电台",
                        tint = MelodiaColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        } else MelodiaIconButton(
            onClick = onToggleLike,
            modifier = Modifier.offset(x = 10.dp)
        ) {
            Icon(
                if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = MelodiaColors.TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
