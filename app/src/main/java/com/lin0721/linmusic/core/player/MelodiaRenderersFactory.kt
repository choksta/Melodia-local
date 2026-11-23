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
 * 在默认渲染器的基础上，接入三样东西：
 *
 * 1. 输出层最外层是音量平衡增益（[TrackGainAudioOutputProvider]）。高精度源走浮点输出时
 *    会绕过 AudioProcessor，增益只能在输出层施加。
 * 2. 再往里是 USB 独占判断（[UsbAudioOutputProvider]），不能直出时回落系统 AudioTrack；
 *    开启浮点输出让高精度源以浮点进入输出层。
 * 3. 自制的 PCM 均衡器挂在 sink 的处理器链上。
 *
 * 三者作用层不同，可以共存：数据流向是
 * 解码 → processors（均衡器）→ OutputProvider（TrackGain → USB → AudioTrack），顺序不会互相干扰。
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
    private val gainState: TrackGainState? = null,
    private val processorFactory: (() -> AudioProcessor)? = null
) : DefaultRenderersFactory(context) {

    init {
        // 云盘与本地 ALAC 等系统解码器不支持的格式交给 ffmpeg 扩展
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .apply {
            val gain = gainState
            if (usbAudio != null) {
                // 独占输出要求浮点直通，避免再经一次整型量化。
                // 注意「sink 用浮点输出」与「向解码器请求浮点」是两件事：后者由 UsbAudioOutputProvider
                // 的格式支持声明决定（走系统输出时降为需转码），否则三星部分 FLAC 会倍速播放并带杂音。
                setEnableFloatOutput(true)
                val usb = UsbAudioOutputProvider(
                    AudioTrackAudioOutputProvider.Builder(context).build(),
                    usbAudio
                )
                setAudioOutputProvider(if (gain != null) TrackGainAudioOutputProvider(usb, gain) else usb)
            } else {
                setEnableFloatOutput(enableFloatOutput)
                if (gain != null) {
                    setAudioOutputProvider(
                        TrackGainAudioOutputProvider(
                            AudioTrackAudioOutputProvider.Builder(context).build(),
                            gain
                        )
                    )
                }
            }
            setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
            processorFactory?.let { setAudioProcessors(arrayOf(it())) }
        }
        .build()
}
