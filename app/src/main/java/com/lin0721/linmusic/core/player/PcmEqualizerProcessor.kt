package com.lin0721.linmusic.core.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG = "PcmEqualizer"

/**
 * 把 [PcmEqualizerCore] 接进 ExoPlayer 的音频管线。
 *
 * 位置在解码之后、AudioTrack 之前，处理的是已经解成 PCM 的采样，因此增益是逐块平滑推进的，
 * 不存在系统 `AudioEffect.Equalizer` 那种「换一组系数 = 一个样本级台阶」的爆音来源。
 *
 * 两台 ExoPlayer（交叉淡化）各自持有一个实例，状态互不影响；
 * 目标曲线通过 [targetsProvider] 每帧缓冲读一次，设置改了下一个缓冲就生效。
 */
internal class PcmEqualizerProcessor(
    private val targetsProvider: () -> AudioEffectTargets
) : BaseAudioProcessor() {

    private var core: PcmEqualizerCore? = null
    private var channelCount = 2
    private var bytesPerSample = 2
    private var scratch = FloatArray(0)

    // 削顶计数：只统计、不改行为。用来客观回答「还破不破音」——
    // 有削顶就必然有谐波失真，没有削顶就说明失真不是这条链路造成的
    private var clipSamples = 0L
    private var peakAbs = 0
    private var reportedClipSamples = 0L
    private var lastReportAt = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            // 开了浮点输出时才有，精度更高，直接吃 float 不用来回换算
            C.ENCODING_PCM_FLOAT -> 4
            // 其余编码（例如直通给硬件解码的压缩音频）交回上游处理，不强行插一手
            else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        core = PcmEqualizerCore(inputAudioFormat.sampleRate, channelCount)
        // 这一行刻意走 android.util.Log 而不是 AppLogger：release 包默认日志级别是 WARN，
        // AppLogger 的 i() 会被丢掉，而这一行是排查「音效没生效/没声音」时唯一的第一手信息
        Log.i(
            TAG,
            "均衡器就绪 ${inputAudioFormat.sampleRate}Hz ${channelCount}ch ${bytesPerSample * 8}bit"
        )
        return inputAudioFormat
    }

    /**
     * 永远声明为活跃。
     *
     * 若按「音效是否开启」动态返回，Media3 只会在重新配置音频链时才会重新询问，
     * 用户打开音效后可能整首歌都不生效。开关由目标增益是否为 0 表达，比换链干净。
     */
    override fun isActive(): Boolean = true

    override fun onFlush() {
        // 换歌/跳转：清掉滤波器状态，别把上一段的振铃带过来
        core?.resetState()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val core = core
        if (core == null) {
            // 没配置成功（理论上不会走到）：原样透传，宁可没音效也不能把声音吞掉
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer.order(ByteOrder.nativeOrder()))
            output.flip()
            return
        }
        val targets = targetsProvider()
        core.setTarget(targets.gainsDb, targets.bassDb)

        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)
        inputBuffer.order(ByteOrder.nativeOrder())

        // 曲线全平（关闭音效）：整段原样拷贝，不做 int16↔float 往返。
        // 往返换算本身会有 1/32768 的量化损失，直通时不该白白付这个代价
        if (core.isBypass) {
            if (output === inputBuffer) {
                // 输入输出是同一块内存（Media3 复用缓冲时会这样），数据原地不动，
                // 只要重新摆好 position/limit。此时 put 自己会抛
                // IllegalArgumentException("The source buffer is this buffer")
                output.clear()
                output.position(size)
            } else {
                output.put(inputBuffer)
                inputBuffer.position(inputBuffer.limit())
            }
            output.flip()
            reportClipping()
            return
        }

        val frames = size / (bytesPerSample * channelCount)
        val sampleCount = frames * channelCount
        if (sampleCount > 0) {
            if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
            if (bytesPerSample == 2) {
                val source = inputBuffer.asShortBuffer()
                for (i in 0 until sampleCount) scratch[i] = source.get(i) * INV_SHORT_MAX
            } else {
                inputBuffer.asFloatBuffer().get(scratch, 0, sampleCount)
            }

            core.process(scratch, frames)

            output.order(ByteOrder.nativeOrder())
            if (bytesPerSample == 2) {
                val destination = output.asShortBuffer()
                for (i in 0 until sampleCount) {
                    // 加了增益后可能越过满刻度，这里硬钳位；放着不管会整数溢出成反相爆音
                    val scaled = scratch[i] * SHORT_MAX
                    val absolute = abs(scaled)
                    if (absolute > peakAbs) peakAbs = absolute.toInt()
                    if (scaled > SHORT_MAX || scaled < SHORT_MIN) clipSamples++
                    destination.put(i, scaled.toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort())
                }
            } else {
                output.asFloatBuffer().put(scratch, 0, sampleCount)
            }
            // 必须自己把父缓冲的 position 推到写入位置：上面用的都是视图的绝对/相对写入，
            // 不会改动父缓冲的 position，而 replaceOutputBuffer 只做了 clear。
            // 少了这一行，flip 之后 limit 还是 0，sink 会拿到永远空的输出、解码管线一路堵死
            //（表现为播放卡在 BUFFERING、进度不动）。
            output.position(sampleCount * bytesPerSample)
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
        reportClipping()
    }

    /**
     * 有削顶才报，最多 15 秒一次。
     *
     * 正常情况下这里永远沉默——它存在的意义是：以后有人反馈「破音」时，
     * 一行日志就能判断失真是不是出自这条链路，不用再靠猜。
     */
    private fun reportClipping() {
        if (clipSamples == reportedClipSamples) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportAt < REPORT_INTERVAL_MS) return
        reportedClipSamples = clipSamples
        lastReportAt = now
        Log.i(TAG, "检测到削顶 累计=$clipSamples 样本 峰值=${peakAbs / 32767f}")
    }

    private companion object {
        const val SHORT_MAX = 32767
        const val SHORT_MIN = -32768
        const val INV_SHORT_MAX = 1f / 32768f
        const val REPORT_INTERVAL_MS = 15_000L
    }
}
