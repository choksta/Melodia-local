package com.lin0721.linmusic.feature.fm.data

interface FmRepository {

    /**
     * 取一批 FM 歌曲。
     *
     * 单次接口返回的首数不固定（实测 1~4 首），所以内部会循环请求直到凑够 [count]，
     * 并跨批去重——同一个 id 在一批里重复出现是常态。
     * 最多请求 4 次，避免服务端连续只返 1 首时无限打点。
     */
    suspend fun nextBatch(count: Int = 3): Result<List<FmSong>>

    /** 把歌曲丢进 FM 垃圾桶，同时更新服务端推荐画像。 */
    suspend fun trash(song: FmSong, playedSeconds: Int = 25): Result<Unit>
}

class FmRepositoryImpl(
    private val api: FmApi
) : FmRepository {

    override suspend fun nextBatch(count: Int): Result<List<FmSong>> = runCatching {
        val collected = LinkedHashMap<Long, FmSong>()
        var attempts = 0
        var lastError: String? = null

        while (collected.size < count && attempts < MAX_ATTEMPTS) {
            attempts++
            val response = api.getPersonalFm()
            if (!response.isSuccess) {
                lastError = "私人FM 返回 code=${response.code}"
                // 第一批就失败说明账号或接口有问题，直接抛；后续批失败则用已拿到的
                if (collected.isEmpty()) error(lastError)
                break
            }
            response.data.forEach { song ->
                if (song.id != 0L) collected.putIfAbsent(song.id, song)
            }
        }

        if (collected.isEmpty()) {
            error(lastError ?: "私人FM 没有返回歌曲")
        }
        collected.values.toList()
    }

    override suspend fun trash(song: FmSong, playedSeconds: Int): Result<Unit> = runCatching {
        val response = api.trashSong(
            FmTrashRequest(
                songId = song.id,
                alg = song.alg.ifBlank { "itembased" },
                time = playedSeconds
            )
        )
        if (!response.isSuccess) error("FM 垃圾桶返回 code=${response.code}")
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
