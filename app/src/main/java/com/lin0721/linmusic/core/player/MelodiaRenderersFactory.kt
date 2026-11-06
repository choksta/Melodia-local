package com.lin0721.linmusic.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.lin0721.linmusic.core.player.usb.UsbAudioDeviceManager
import com.lin0721.linmusic.core.player.usb.UsbAudioOutputProvider

/**
 * 在默认渲染器的基础上，接入两样东西：
 *
 * 1. 输出层先交给 USB 独占判断（[UsbAudioOutputProvider]），不能直出时回落系统 AudioTrack；
 *    开启浮点输出让高精度源以浮点进入输出层。
 * 2. 自制的 PCM 均衡器挂在 sink 的处理器链上。
 *
 * 两者作用层不同，可以共存：PCM 处理器在 sink 内部处理样本，之后才交给 OutputProvider 送出，
 * 数据流向是 解码 → processors → OutputProvider，顺序不会互相干扰。
 *
 * [DefaultRenderersFactory.buildAudioSink] 是官方留的扩展点，默认实现就是
 * `DefaultAudioSink.Builder(context).setEnableFloatOutput(..).setEnableAudioOutputPlaybackParameters(..).build()`，
 * 这里逐项对齐后再多挂处理器与输出层——不重复设置 audio capabilities，
 * 让 sink 自己按当前输出设备判断能力，跟默认行为保持一致。
 *
 * 其余处理器（静音跳过、变速变调的音速处理）由 `setAudioProcessors` 保留下来的默认链负责。
 * 每个 sink 都新建一个处理器实例：交叉淡化时两台播放器同时在跑，共用一个实例会把滤波器状态搅在一起。
 */
@OptIn(UnstableApi::class)
internal class MelodiaRenderersFactory(
    context: Context,
    private val usbAudio: UsbAudioDeviceManager? = null,
    private val processorFactory: (() -> AudioProcessor)? = null
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .apply {
            if (usbAudio != null) {
                // 独占输出要求浮点直通，避免再经一次整型量化
                setEnableFloatOutput(true)
                setAudioOutputProvider(
                    UsbAudioOutputProvider(
                        AudioTrackAudioOutputProvider.Builder(context).build(),
                        usbAudio
                    )
                )
            } else {
                setEnableFloatOutput(enableFloatOutput)
            }
            setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            processorFactory?.let { setAudioProcessors(arrayOf(it())) }
        }
        .build()
}
