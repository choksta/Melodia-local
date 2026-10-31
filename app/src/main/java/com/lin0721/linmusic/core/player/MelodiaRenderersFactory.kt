package com.lin0721.linmusic.core.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 在默认渲染器的基础上，把自制的 PCM 均衡器接进音频链。
 *
 * [DefaultRenderersFactory.buildAudioSink] 是官方留的扩展点，默认实现就是
 * `DefaultAudioSink.Builder(context).setEnableFloatOutput(..).setEnableAudioOutputPlaybackParameters(..).build()`，
 * 这里逐项对齐后再多挂一个处理器——不重复设置 audio capabilities，
 * 让 sink 自己按当前输出设备判断能力，跟默认行为保持一致。
 *
 * 其余处理器（静音跳过、变速变调的音速处理）由 `setAudioProcessors` 保留下来的默认链负责。
 * 每个 sink 都新建一个处理器实例：交叉淡化时两台播放器同时在跑，共用一个实例会把滤波器状态搅在一起。
 */
internal class MelodiaRenderersFactory(
    context: Context,
    private val processorFactory: () -> AudioProcessor
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
        .setAudioProcessors(arrayOf(processorFactory()))
        .build()
}
