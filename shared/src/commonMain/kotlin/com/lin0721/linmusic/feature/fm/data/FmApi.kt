package com.lin0721.linmusic.feature.fm.data

import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 私人 FM 接口。
 *
 * 路径是 /weapi/v1/radio/get —— 注意不是 /personal/fm，后者是第三方代理服务暴露的
 * 外部路由名，直接打网易云会 404。
 * 不带参数也会返回推荐（服务端按账号画像推），mode 可切换推荐口味。
 */
interface FmApi {

    @POST("/weapi/v1/radio/get")
    suspend fun getPersonalFm(
        @Body body: PersonalFmRequest = PersonalFmRequest()
    ): PersonalFmResponse

    /**
     * FM 垃圾桶：告诉服务端「这首不想听」。
     *
     * 与直接跳过的区别：垃圾桶会更新推荐画像，后续不会再推同类型；
     * 单纯 playNext 只是本地跳过，服务端不知道。
     */
    @POST("/weapi/radio/trash")
    suspend fun trashSong(
        @Body body: FmTrashRequest
    ): FmTrashResponse
}

@Serializable
data class PersonalFmRequest(
    // DEFAULT / FAMILIAR（熟悉）/ EXPLORE（探索）/ SCENE_RCMD（场景）
    val mode: String = "DEFAULT"
)

@Serializable
data class PersonalFmResponse(
    val code: Int = 0,
    val data: List<FmSong> = emptyList()
) {
    val isSuccess: Boolean get() = code == 200
}

@Serializable
data class FmSong(
    val id: Long = 0,
    val name: String = "",
    // 字段名有两套：新版接口给 ar/al，老接口（/weapi/v1/radio/get）给 artists/album。
    // 只声明 ar/al 的话老接口这两项会静默反序列化成空——歌手和封面都没了，歌名却正常，
    // 排查时很容易怀疑到图片加载上。两套都接，用下面的 computed 属性归一。
    val ar: List<Artist> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val al: Album = Album(),
    val album: Album = Album(),
    val dt: Long = 0,
    val fee: Int = 0,
    // 垃圾桶接口要把 alg 原样回传，服务端据此定位是哪一套推荐算法给出的这首歌
    val alg: String = ""
) {
    val resolvedArtists: List<Artist> get() = ar.ifEmpty { artists }

    val resolvedAlbum: Album get() = if (al.picUrl.isNotEmpty() || al.id != 0L) al else album

    val artistText: String
        get() = resolvedArtists.joinToString(" / ") { it.name }.ifBlank { "未知歌手" }

    val coverUrl: String get() = resolvedAlbum.picUrl
}

@Serializable
data class FmTrashRequest(
    val songId: Long,
    // 服务端未下发 alg 时用 itembased（基于物品的协同过滤），是 FM 的默认通道
    val alg: String = "itembased",
    // 播放时长（秒）。服务端用它区分「听完才扔」和「听两秒就扔」，权重不同
    val time: Int = 25
)

@Serializable
data class FmTrashResponse(
    val code: Int = 0
) {
    val isSuccess: Boolean get() = code == 200
}
