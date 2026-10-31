package com.lin0721.linmusic.core.player

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lin0721.linmusic.core.log.AppLogger

private const val TAG = "CrossfadePlayer"
private const val FADE_STEP_MS = 16L
// 启动淡入：冷启动/暂停恢复时 AudioTrack 重新起动、在途效果链的滤波器从零状态启动，
// 两者都会让第一帧出现过冲——听感是「开头一下特别大声」或「啪」。90ms 的音量斜坡足够抹平。
private const val STARTUP_FADE_MS = 90L
// 等不到真正出声（缓冲卡住等）时兜底恢复满音量，避免音量被永久留在 0
private const val STARTUP_FADE_TIMEOUT_MS = 1600L

// 会话播放器：内部两台 ExoPlayer 轮换，对外始终是同一个 Player。
// 带 crossfadeMs 的切歌先在空闲播放器里静音缓冲，就绪后才接管并等功率交叉淡化；
// 缓冲期间 setMediaItems 的 future 保持未完成，SimpleBasePlayer 用占位状态对外只报一次切歌
@OptIn(UnstableApi::class)
class CrossfadePlayer(
    private val primary: ExoPlayer,
    private val secondary: ExoPlayer,
    private val audioAttributes: AudioAttributes
) : ForwardingSimpleBasePlayer(primary) {

    private val handler = Handler(primary.applicationLooper)

    private var handleAudioFocus = true
    private var metadataTransformer: ((MediaMetadata) -> MediaMetadata)? = null

    private var pendingIncoming: ExoPlayer? = null
    private var pendingFuture: SettableFuture<Unit>? = null
    private var pendingFadeMs = 0L

    private var outgoing: ExoPlayer? = null
    private var outgoingStartGain = 1f
    private var fadeStartElapsedMs = 0L
    private var fadeDurationMs = 0L

    // 启动淡入：pending 表示已把音量预置为 0、正在等真正出声；
    // runnable 是正在执行的音量斜坡
    private var startupFadePending = false
    private var startupFadeRunnable: Runnable? = null
    private val startupFadeTimeout = Runnable {
        if (!startupFadePending) return@Runnable
        startupFadePending = false
        if (outgoing == null) active.volume = 1f
        AppLogger.w(TAG, "启动淡入等待超时，恢复满音量")
    }

    private val active: ExoPlayer
        get() = if (player === secondary) secondary else primary

    // 逐字歌词读取当前接管播放的 ExoPlayer，切换两台播放器时自动跟随。
    // 与其余 Player API 一样，只能从 applicationLooper 所在线程调用。
    internal fun currentPositionSample(): PlaybackPositionSample? = active.run {
        currentMediaItem?.let { PlaybackPositionSample(it.mediaId, currentPosition) }
    }

    private val fadeStep = object : Runnable {
        override fun run() {
            val out = outgoing ?: return
            val progress = ((SystemClock.elapsedRealtime() - fadeStartElapsedMs).toFloat() / fadeDurationMs).coerceIn(0f, 1f)
            out.volume = CrossfadePolicy.fadeOutGain(progress, outgoingStartGain)
            active.volume = CrossfadePolicy.fadeInGain(progress)
            if (progress >= 1f) {
                releaseOutgoing()
            } else {
                handler.postDelayed(this, FADE_STEP_MS)
            }
        }
    }

    init {
        primary.setAudioAttributes(audioAttributes, handleAudioFocus)
        secondary.setAudioAttributes(audioAttributes, false)
        val internalListener = InternalListener()
        primary.addListener(internalListener)
        secondary.addListener(internalListener)
    }

    fun setHandleAudioFocus(handle: Boolean) {
        handleAudioFocus = handle
        active.setAudioAttributes(audioAttributes, handle)
    }

    fun setPreferredAudioDevice(device: AudioDeviceInfo?) {
        primary.setPreferredAudioDevice(device)
        secondary.setPreferredAudioDevice(device)
    }

    fun setMetadataTransformer(transformer: ((MediaMetadata) -> MediaMetadata)?) {
        metadataTransformer = transformer
        invalidateState()
    }

    // 外部歌词等元数据来源变化时刷新对外状态
    fun notifyMetadataChanged() {
        invalidateState()
    }

    override fun getState(): State {
        val base = super.getState()
        // 通知栏上/下一首按钮依赖这两个命令，底层单曲播放器本身不会提供
        val commands = base.availableCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
        // 淡化期间音量每帧变化，对外固定为 1，避免向所有控制器高频广播
        val builder = base.buildUpon().setAvailableCommands(commands).setVolume(1f)
        val transformer = metadataTransformer
        if (transformer != null && !base.timeline.isEmpty) {
            val activeIndex = active.currentMediaItemIndex
            val transformedTimeline = TransformedTimeline(base.timeline, activeIndex, transformer)
            val transformedMetadata = transformer(base.currentMetadata)
            builder.setPlaylist(transformedTimeline, base.currentTracks, transformedMetadata)
        }
        return builder.build()
    }

    // 父类在切歌等待期间把占位状态标成 BUFFERING；此时旧歌仍在出声，沿用 READY，
    // 否则 PlayerManager 会判定为未播放，连续切歌时不再下发淡化
    override fun getPlaceholderState(suggestedPlaceholderState: State): State {
        if (pendingIncoming == null) return suggestedPlaceholderState
        return suggestedPlaceholderState.buildUpon()
            .setPlaybackState(Player.STATE_READY)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        val extras = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaMetadata?.extras
        val fadeMs = extras?.getLong(CrossfadePolicy.EXTRA_CROSSFADE_MS, 0L) ?: 0L
        val startOffsetMs = extras?.getLong(CrossfadePolicy.EXTRA_CROSSFADE_START_MS, 0L) ?: 0L
        // 上一轮还在缓冲就被新请求顶替：缓冲中的播放器直接复用，旧 future 推迟到新操作入队后再完成，
        // 否则两次操作之间 SimpleBasePlayer 会读到旧播放器状态，多报一次切回旧歌
        pendingFuture?.let { stale -> handler.post { stale.set(Unit) } }
        pendingFuture = null
        val abandoned = pendingIncoming
        pendingIncoming = null
        if (fadeMs <= 0L || !active.isPlaying) {
            abandoned?.run {
                stop()
                clearMediaItems()
            }
            releaseOutgoing()
            return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
        }
        // 新歌跳过开头静音，直接从有效开头起播
        return startCrossfade(mediaItems, startIndex, maxOf(startPositionMs, startOffsetMs), fadeMs)
    }

    // 上/下一首已由会话回调 onPlayerCommandRequest 转给 PlayerManager；
    // 底层单曲播放器的上一首会退化成回到开头，这里直接吞掉
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Futures.immediateVoidFuture()
            else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) {
            completePendingImmediately()
            cancelStartupFade()
            releaseOutgoing()
        } else if (outgoing == null && !active.isPlaying) {
            // 从静止开始播放：先把音量压到 0，等真正出声（isPlaying 变 true）再斜坡升上来。
            // 缓冲期间本就没有声音，压 0 不影响体验。
            primeStartupFade()
        }
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleStop(): ListenableFuture<*> {
        completePendingImmediately()
        cancelStartupFade()
        releaseOutgoing()
        return super.handleStop()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        other(active).repeatMode = repeatMode
        return super.handleSetRepeatMode(repeatMode)
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacksAndMessages(null)
        pendingFuture?.set(Unit)
        pendingFuture = null
        pendingIncoming = null
        outgoing = null
        primary.release()
        secondary.release()
        return Futures.immediateVoidFuture()
    }

    private fun startCrossfade(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        fadeMs: Long
    ): ListenableFuture<*> {
        // 启动淡入与交叉淡化都在改音量，不能同时进行：先收掉淡入，把当前播放器音量还原
        cancelStartupFade()
        active.volume = 1f
        // 上一轮淡出还没结束时先停掉它；正在淡入的当前播放器保持现有音量，成为新一轮的淡出方
        stopOutgoing()
        val incoming = other(active)
        incoming.stop()
        incoming.volume = 0f
        incoming.repeatMode = active.repeatMode
        incoming.setMediaItems(mediaItems, startIndex, startPositionMs)
        incoming.playWhenReady = true
        incoming.prepare()

        val future = SettableFuture.create<Unit>()
        pendingIncoming = incoming
        pendingFuture = future
        pendingFadeMs = fadeMs
        return future
    }

    private fun onIncomingReady(incoming: ExoPlayer) {
        // 同一台播放器可能已被新请求复用并重新缓冲，以执行时的状态为准
        if (pendingIncoming !== incoming || incoming.playbackState != Player.STATE_READY) return
        val previous = active
        takeOver(incoming)
        outgoing = previous
        outgoingStartGain = previous.volume
        fadeStartElapsedMs = SystemClock.elapsedRealtime()
        fadeDurationMs = pendingFadeMs.coerceAtLeast(1L)
        handler.post(fadeStep)
        finishPending()
    }

    // 焦点必须先从旧播放器释放再交给新播放器，否则两台互抢焦点会被系统暂停
    private fun takeOver(incoming: ExoPlayer) {
        val previous = active
        previous.setAudioAttributes(audioAttributes, false)
        incoming.setAudioAttributes(audioAttributes, handleAudioFocus)
        setPlayer(incoming)
    }

    private fun completePendingImmediately(playWhenReady: Boolean = true) {
        val incoming = pendingIncoming ?: return
        val previous = active
        takeOver(incoming)
        incoming.volume = 1f
        if (!playWhenReady) incoming.playWhenReady = false
        previous.stop()
        previous.clearMediaItems()
        previous.volume = 1f
        finishPending()
    }

    private fun finishPending() {
        pendingIncoming = null
        val future = pendingFuture
        pendingFuture = null
        future?.set(Unit)
    }

    // 中止淡化：停掉淡出方，当前播放器恢复满音量
    private fun releaseOutgoing() {
        val hadOutgoing = outgoing != null
        stopOutgoing()
        if (hadOutgoing) active.volume = 1f
    }

    private fun stopOutgoing() {
        handler.removeCallbacks(fadeStep)
        val out = outgoing ?: return
        outgoing = null
        out.stop()
        out.clearMediaItems()
        out.volume = 1f
    }

    // 预置：把音量压到 0 并挂超时兜底，等 isPlaying 变 true 的那一刻再开始升
    private fun primeStartupFade() {
        cancelStartupFade()
        startupFadePending = true
        active.volume = 0f
        handler.postDelayed(startupFadeTimeout, STARTUP_FADE_TIMEOUT_MS)
    }

    // 真正出声的瞬间：90ms 从 0 升到满，把 AudioTrack 起动与效果链滤波器的过冲压进斜坡里
    private fun runStartupFade() {
        if (!startupFadePending) return
        startupFadePending = false
        handler.removeCallbacks(startupFadeTimeout)
        startupFadeRunnable?.let { handler.removeCallbacks(it) }

        val target = active
        val startMs = SystemClock.elapsedRealtime()
        val runnable = object : Runnable {
            override fun run() {
                // 已进入交叉淡化 / 转发目标被换掉：音量交给淡化逻辑或新播放器自己管
                if (outgoing != null || target !== active) {
                    if (target !== active) target.volume = 1f
                    startupFadeRunnable = null
                    return
                }
                val progress = ((SystemClock.elapsedRealtime() - startMs).toFloat() / STARTUP_FADE_MS)
                    .coerceIn(0f, 1f)
                target.volume = progress
                if (progress < 1f) {
                    handler.postDelayed(this, FADE_STEP_MS)
                } else {
                    startupFadeRunnable = null
                }
            }
        }
        startupFadeRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelStartupFade() {
        startupFadePending = false
        handler.removeCallbacks(startupFadeTimeout)
        startupFadeRunnable?.let { handler.removeCallbacks(it) }
        startupFadeRunnable = null
    }

    private fun other(player: ExoPlayer): ExoPlayer = if (player === primary) secondary else primary

    private inner class InternalListener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val exo = player as? ExoPlayer ?: return
            if (exo === pendingIncoming && exo.playbackState == Player.STATE_READY) {
                // 在 ExoPlayer 回调内切换转发目标会重入其监听分发，推迟到下一帧
                handler.post { onIncomingReady(exo) }
            }
            // 新歌迟迟未就绪而旧歌已放完或出错：立即接管，让对外状态如实显示新歌在缓冲，避免一直停在占位的 READY
            if (exo === active && pendingIncoming != null &&
                (exo.playbackState == Player.STATE_ENDED || exo.playerError != null)
            ) {
                handler.post { completePendingImmediately() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // 新歌缓冲失败时直接接管，让错误经会话状态传给 PlayerManager 走原有的跳过逻辑
            val incoming = pendingIncoming ?: return
            if (incoming.playerError == null) return
            AppLogger.w(TAG, "交叉淡化目标加载失败，改为直接切换", error)
            completePendingImmediately()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 音频焦点丢失等系统暂停只作用于当前播放器：淡出方同步停掉，缓冲中的新歌接管后保持暂停
            if (playWhenReady || active.playWhenReady) return
            completePendingImmediately(playWhenReady = false)
            releaseOutgoing()
        }

        // 只在「当前播放器真正出声」这一刻启动淡入：缓冲期间 isPlaying 一直是 false，
        // 用 playWhenReady 当触发点会白做（斜坡早就跑完了才出声）
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            val exo = player as? ExoPlayer ?: return
            if (exo !== active) return
            runStartupFade()
        }
    }
}

@OptIn(UnstableApi::class)
private class TransformedTimeline(
    private val timeline: Timeline,
    private val activeMediaItemIndex: Int,
    private val transformer: (MediaMetadata) -> MediaMetadata
) : Timeline() {

    override fun getWindowCount(): Int = timeline.windowCount

    override fun getNextWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        return timeline.getNextWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }

    override fun getPreviousWindowIndex(windowIndex: Int, repeatMode: Int, shuffleModeEnabled: Boolean): Int {
        return timeline.getPreviousWindowIndex(windowIndex, repeatMode, shuffleModeEnabled)
    }

    override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int {
        return timeline.getLastWindowIndex(shuffleModeEnabled)
    }

    override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
        return timeline.getFirstWindowIndex(shuffleModeEnabled)
    }

    override fun getWindow(
        windowIndex: Int,
        window: Window,
        defaultPositionProjectionUs: Long
    ): Window {
        val w = timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
        if (windowIndex == activeMediaItemIndex || activeMediaItemIndex == -1) {
            val originalItem = w.mediaItem
            w.mediaItem = originalItem.buildUpon()
                .setMediaMetadata(transformer(originalItem.mediaMetadata))
                .build()
        }
        return w
    }

    override fun getPeriodCount(): Int = timeline.periodCount

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        return timeline.getPeriod(periodIndex, period, setIds)
    }

    override fun getIndexOfPeriod(uid: Any): Int {
        return timeline.getIndexOfPeriod(uid)
    }

    override fun getUidOfPeriod(periodIndex: Int): Any {
        return timeline.getUidOfPeriod(periodIndex)
    }
}

