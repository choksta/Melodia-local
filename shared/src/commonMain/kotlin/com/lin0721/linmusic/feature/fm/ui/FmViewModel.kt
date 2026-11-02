package com.lin0721.linmusic.feature.fm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.player.PlaybackController
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.feature.fm.data.FmRepository
import com.lin0721.linmusic.feature.fm.data.FmSong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FmUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    /** 当前播放的是不是 FM 流。false 表示用户在听别的歌，页面显示「开始漫游」。 */
    val isFmActive: Boolean = false
)

class FmViewModel(
    private val repository: FmRepository,
    val playerManager: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(FmUiState())
    val state: StateFlow<FmUiState> = _state.asStateFlow()

    /**
     * 当前这首歌的完整数据。
     *
     * 不从 PlaybackController 的 QueueItem 取：那是给播放器用的最小参数集
     * （songId/title/artist/coverUrl），字段在链路里可能被规范化或截断，
     * 页面要显示歌手和封面，直接持有原始 FmSong 最可靠。
     */
    private val _current = MutableStateFlow<FmSong?>(null)
    val current: StateFlow<FmSong?> = _current.asStateFlow()

    // 本次会话拉到的歌，按 id 反查（切歌时 currentQueueItem 只给 id）
    private val songCache = LinkedHashMap<Long, FmSong>()

    private var extendJob: Job? = null
    private var requestJob: Job? = null

    init {
        // 跟随播放器：切歌时判断是否仍在 FM 流内，并在队列见底前补一批
        viewModelScope.launch {
            playerManager.currentQueueItem.collect { item ->
                val active = isFmActive()
                _state.update { it.copy(isFmActive = active) }
                if (active && item != null) {
                    songCache[item.songId]?.let { _current.value = it }
                    maybeExtend()
                }
            }
        }
    }

    /** 进入页面时调用。已经在播 FM 就接着播，不要打断。 */
    fun enter() {
        // 必须同时满足「播放器在播 FM」和「本页手里有这首歌的数据」。
        // 进程重启后播放器会恢复上次的 FM 队列，playContext 仍是 fm_personal，
        // 但本 ViewModel 是新实例、缓存为空——只看 context 会误判成「已在播」，
        // 页面于是停在空状态，而底下播放条还在放歌。
        if (isFmActive() && _current.value != null) return
        startNewStream()
    }

    /** 重新开一条 FM 流（首次进入，或用户主动重开）。 */
    fun startNewStream() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.nextBatch(BATCH_SIZE)
                .onSuccess { songs ->
                    if (songs.isEmpty()) {
                        _state.update { it.copy(isLoading = false, error = "没有拿到推荐歌曲") }
                        return@onSuccess
                    }
                    songs.forEach { songCache[it.id] = it }
                    val items = songs.map { it.toQueueItem() }
                    playerManager.playQueue(items, 0, CONTEXT_FM)
                    _current.value = songs.first()
                    _state.update { it.copy(isLoading = false, isFmActive = true) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, error = error.message ?: "私人 FM 加载失败")
                    }
                }
        }
    }

    fun next() {
        if (!isFmActive()) {
            startNewStream()
            return
        }
        val queueSize = playerManager.queue.value.size
        val index = playerManager.currentIndex.value
        if (index < queueSize - 1) {
            playerManager.playNext()
        } else {
            // 队尾了：先把新的一批接上，再跳
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true) }
                repository.nextBatch(BATCH_SIZE)
                    .onSuccess { songs ->
                        playerManager.appendToQueue(songs.map { it.toQueueItem() })
                        playerManager.playNext()
                        _state.update { it.copy(isLoading = false) }
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(isLoading = false, error = error.message ?: "暂时拉不到新歌")
                        }
                    }
            }
        }
    }

    /**
     * 不喜欢：先丢进服务端垃圾桶（更新推荐画像），再跳下一首。
     *
     * 垃圾桶失败不影响播放——本地照跳，只是服务端不知道这轮偏好。
     */
    fun dislike() {
        val current = playerManager.currentQueueItem.value ?: return
        val playedSeconds = (playerManager.currentPosition.value / 1000L).toInt()
        viewModelScope.launch {
            repository.trash(
                FmSong(id = current.songId, name = current.title),
                playedSeconds = playedSeconds.coerceIn(1, 300)
            )
        }
        next()
    }

    fun togglePlayPause() {
        playerManager.togglePlayPause()
    }

    private fun isFmActive(): Boolean =
        playerManager.playContext.value == CONTEXT_FM &&
            playerManager.currentQueueItem.value != null

    /** 队列剩余不足两首时补一批，让 FM 断续处不出现停顿。 */
    private fun maybeExtend() {
        val queueSize = playerManager.queue.value.size
        val index = playerManager.currentIndex.value
        if (queueSize - index > EXTEND_THRESHOLD) return
        if (extendJob?.isActive == true) return

        extendJob = viewModelScope.launch {
            repository.nextBatch(BATCH_SIZE)
                .onSuccess { songs ->
                    if (songs.isNotEmpty()) {
                        songs.forEach { songCache[it.id] = it }
                        playerManager.appendToQueue(songs.map { it.toQueueItem() })
                    }
                }
            // 补批失败不打扰用户：当前队列还能放完，下次切歌会再试
        }
    }

    private fun FmSong.toQueueItem() = QueueItem(
        songId = id,
        title = name,
        artist = artistText,
        coverUrl = coverUrl
    )

    companion object {
        /** 播放上下文标记。FM 队列与普通歌单队列靠它区分。 */
        const val CONTEXT_FM = "fm_personal"

        private const val BATCH_SIZE = 3
        private const val EXTEND_THRESHOLD = 1
    }
}
