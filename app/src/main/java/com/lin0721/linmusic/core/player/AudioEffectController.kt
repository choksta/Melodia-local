package com.lin0721.linmusic.core.player

import androidx.media3.common.audio.AudioProcessor

/**
 * 目标曲线：五段均衡增益（dB）+ 低音增强低架增益（dB）。
 *
 * 数组长度固定为 [AudioEffectSettings.REFERENCE_FREQS_HZ] 的长度，播放线程每个缓冲读一次。
 */
class AudioEffectTargets(
    @JvmField val gainsDb: FloatArray,
    @JvmField val bassDb: Float
) {
    companion object {
        val FLAT = AudioEffectTargets(
            FloatArray(AudioEffectSettings.REFERENCE_FREQS_HZ.size),
            0f
        )
    }
}

/**
 * 音效设置的持有者与处理器工厂。
 *
 * 之前这里挂的是系统 `AudioEffect.Equalizer` / `BassBoost`，靠把电平切成极小步来掩盖爆音，
 * 但「换一组滤波器系数」这件事本身就会在样本上留下不连续，步长再细也只是听感上变小。
 * 现在改成在解码后的 PCM 上做均衡（[PcmEqualizerCore]），增益连续推进，不存在台阶。
 *
 * 因此这个类不再需要 audio session、独立线程、渐变调度——只剩「把设置翻译成目标增益」这一件事，
 * 两只播放器（交叉淡化）各自的处理器都从这里读同一个目标。
 */
class AudioEffectController {

    @Volatile
    private var targets: AudioEffectTargets = AudioEffectTargets.FLAT

    /** 每个音频 sink 一个处理器实例（两台播放器两套滤波器状态），但共享同一份目标 */
    fun newProcessor(): AudioProcessor = PcmEqualizerProcessor { targets }

    /**
     * 设置变化时调用。这里不加锁也不会读到半个状态：
     * [targets] 是整体替换的不可变对象，播放线程要么读到旧的、要么读到新的。
     */
    fun update(enabled: Boolean, preset: Int, customBands: List<Int>, bassStrength: Int) {
        if (!enabled) {
            targets = AudioEffectTargets.FLAT
            return
        }
        val curve = if (preset == AudioEffectSettings.PRESET_CUSTOM) {
            customBands
        } else {
            AudioEffectSettings.levelsForPreset(preset)
        }
        // 预设曲线是毫贝（100 = 1dB），这里换成 dB 给滤波器用
        val gains = FloatArray(AudioEffectSettings.REFERENCE_FREQS_HZ.size) { index ->
            (curve.getOrNull(index) ?: 0) / 100f
        }
        val bassDb = bassStrength.coerceIn(0, 1000) / 1000f * AudioEffectSettings.BASS_SHELF_MAX_DB
        targets = AudioEffectTargets(gains, bassDb)
    }
}
