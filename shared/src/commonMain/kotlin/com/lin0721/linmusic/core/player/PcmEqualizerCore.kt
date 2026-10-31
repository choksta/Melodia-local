package com.lin0721.linmusic.core.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 单个双二阶滤波器（RBJ Cookbook 系数），Direct Form II 转置结构。
 *
 * 系数与状态都用 Double：一首歌要过几百万个采样，Float 的累积误差会让低频段的
 * 直流偏移慢慢漂起来（表现为低频越来越闷）。
 */
internal class BiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** 峰值型（peaking）：只影响中心频率附近，是五段均衡器的基本单元 */
    fun setPeaking(sampleRate: Int, freqHz: Int, q: Double, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    /** 低架型（low shelf）：只抬某个频率以下的全部内容，用来做「低音增强」 */
    fun setLowShelf(sampleRate: Int, freqHz: Int, gainDb: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sampleRate
        val cosW = cos(w0)
        // S = 1（临界阻尼），听感上最不容易出「桶音」
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val k = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cosW + k
        b0 = a * ((a + 1.0) - (a - 1.0) * cosW + k) / a0
        b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW) / a0
        b2 = a * ((a + 1.0) - (a - 1.0) * cosW - k) / a0
        a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW) / a0
        a2 = ((a + 1.0) + (a - 1.0) * cosW - k) / a0
    }

    fun resetState() {
        z1 = 0.0
        z2 = 0.0
    }

    /**
     * 归一化频率 [w]（rad/sample）处的幅度响应，用来算整条曲线叠加后的峰值。
     *
     * H(z) = (b0 + b1·z⁻¹ + b2·z⁻²) / (1 + a1·z⁻¹ + a2·z⁻²)，取 |H(e^jw)|。
     */
    fun magnitudeAt(w: Double): Double {
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        return if (den < 1e-12) 1.0 else num / den
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * 跑在解码后 PCM 上的均衡器（五段峰值 + 一段低架）。
 *
 * 为什么不用系统 `AudioEffect.Equalizer`：那个接口只提供「设一个新电平」这一种操作，
 * 增益一变就是滤波器系数整体换一组，样本上留下一个不连续——低频段振铃时间长，听感就是
 * 「啪」的一下。把电平切成 0.12dB 小步也压不掉最后那一点点，因为台阶永远存在。
 *
 * 这里改成在采样流里做：每 [BLOCK_FRAMES] 帧才把当前增益朝目标挪一点点并重算系数，
 * 单次跳变不超过 [MAX_SLEW_DB_PER_BLOCK]，也就是 48kHz 下每 1.3ms 只变 0.055dB。
 * 增益是连续逼近的，不存在台阶，所以不可能有爆音。
 *
 * 纯 Kotlin、无 Android 依赖，可以在桌面单测里直接喂正弦波验证频响。
 */
class PcmEqualizerCore(
    private val sampleRate: Int,
    private val channelCount: Int,
    bandFreqsHz: List<Int> = AudioEffectSettings.REFERENCE_FREQS_HZ
) {
    private companion object {
        /** 每多少帧重算一次系数：48kHz 下 1.3ms，既听得足够平滑又不浪费算力 */
        const val BLOCK_FRAMES = 64

        /** 每次重算最多追多少 dB，决定渐变快慢：约 40dB/s，8.5dB 的切换 200ms 走完 */
        const val MAX_SLEW_DB_PER_BLOCK = 0.055

        /** 峰值型滤波器的 Q：1.0 时五段之间过渡自然，不会出现明显的分带感 */
        const val PEAK_Q = 1.0

        /** 低音增强的架子频率 */
        const val BASS_SHELF_HZ = 100

        /** 增益小于这个值就不再重算系数，避免渐变结束后空转 */
        const val GAIN_EPSILON = 1e-4

        /** 扫峰用的频点：20Hz~20kHz 对数分布，密到不会漏掉相邻频段叠加出来的峰 */
        val ANALYSIS_FREQS: DoubleArray = DoubleArray(96) { index ->
            20.0 * 10.0.pow(3.0 * index / 95.0)
        }

        /**
         * 预衰减补多少：1.0 = 全补（绝不削顶，但整体音量会小一大截），
         * 0.5 = 只补一半，剩下的交给限压器在真的快溢出时压那一下。
         *
         * 全补太保守：实测这首歌带电子预设峰值只到 0.62，说明大部分时候根本没顶到天花板，
         * 却让用户白丢 6dB 音量。补一半 + 按需限压，听感上音量接近「关闭音效」。
         */
        const val PREAMP_COMPENSATION = 0.5

        /** 限压器阈值：留一点余量给后面的整数换算 */
        const val LIMITER_THRESHOLD = 0.92

        /**
         * 预读时长。
         *
         * 限压器如果只能「事后」反应，峰值那一两个采样早就冲过天花板了（实测漏掉 138 个，
         * 峰值冲到 1.078）。所以把音频延迟 2ms 再输出，用还没播到的那一段来算增益——
         * 增益总是先到位，峰不会被漏掉。2ms 的延迟对听歌没有任何影响。
         */
        const val LIMITER_LOOKAHEAD_MS = 2.0

        // 放开要慢，否则会听见「呼吸」；60ms 足够跟住鼓点又不拖尾
        const val LIMITER_RELEASE_MS = 60.0
    }

    private val peakFreqs = bandFreqsHz
    private val peakFilters = Array(peakFreqs.size) { Array(channelCount) { BiquadFilter() } }
    private val peakCurrent = DoubleArray(peakFreqs.size)
    private val peakTarget = DoubleArray(peakFreqs.size)

    private val shelfFilters = Array(channelCount) { BiquadFilter() }
    private var shelfCurrent = 0.0
    private var shelfTarget = 0.0

    // 预衰减：整条曲线的峰值有 +N dB，就先整体压低 N dB 再进滤波器。
    // 不做这一步，抬升频段会把原本就贴着满刻度的母带推过 0dBFS，听感就是「破音、糊」。
    private var preampCurrent = 0.0
    private var preampTarget = 0.0
    private var preampLinear = 1.0

    // 只用于扫合成响应的临时滤波器，不碰正在跑的那组
    private val analysisFilters = Array(bandFreqsHz.size + 1) { BiquadFilter() }

    // 限压器：曲线一抬就有削顶风险，但绝大多数时间峰离天花板还远，
    // 所以不预先压低，只在真的会超时把那一小段压下来。
    // 左右声道联动（用同一帧的最大值算增益），否则大动态时声像会左右漂。
    private var limiterGain = 1.0
    private var limiterEngaged = false
    private val limiterRelease: Double = 1.0 - kotlin.math.exp(-1.0 / (sampleRate * LIMITER_RELEASE_MS / 1000.0))
    private val frameScratch = DoubleArray(channelCount)
    // 预读延迟线：写当前帧、读 2ms 前那一帧，增益永远先于峰值到位
    private val lookaheadFrames = (sampleRate * LIMITER_LOOKAHEAD_MS / 1000.0).toInt().coerceAtLeast(1)
    private val delayLines = Array(channelCount) { DoubleArray(lookaheadFrames) }
    private var delayIndex = 0
    private val delayedFrame = DoubleArray(channelCount)

    // 首次由全平开始：冷启动/换歌时音效是淡入的，不会带着陡峭曲线处理第一帧
    private var initialized = false

    /**
     * 当前是否完全直通（曲线全平、预衰减为 1、限压器未介入）。
     *
     * 关闭音效时如果还逐采样跑 6 个 biquad，等于白烧 CPU：实测开关音效的 CPU 差
     * 小到测不出来，正是因为「关闭」也在满负荷计算。判据同时看目标和当前值——
     * 目标已经回到全平但增益还在往下走的过渡期不能旁路，否则会留下一个台阶。
     */
    var isBypass: Boolean = false
        private set

    /**
     * 目标增益（dB），直接对应界面上的五条滑杆。
     *
     * 用 FloatArray 而不是 List：这个方法每个音频缓冲都会被调一次（约 47 次/秒），
     * 传 List 会白白造垃圾。
     */
    fun setTarget(gainsDb: FloatArray, bassDb: Float) {
        // 这个方法每个音频缓冲都会被调一次（~47 次/秒），但目标值绝大多数时候没变：
        // 只有真变了才算扫峰，否则每秒白扫 47 遍（96 个频点 × 6 个滤波器）
        var changed = false
        // 满长度时直接下标取，省掉 getOrNull 的越界检查和每次的装箱
        val direct = gainsDb.size >= peakTarget.size
        for (i in peakTarget.indices) {
            val value = (if (direct) gainsDb[i] else 0f).toDouble()
            if (peakTarget[i] != value) {
                peakTarget[i] = value
                changed = true
            }
        }
        val bass = bassDb.coerceIn(0f, AudioEffectSettings.BASS_SHELF_MAX_DB).toDouble()
        if (shelfTarget != bass) {
            shelfTarget = bass
            changed = true
        }
        if (changed) {
            val peakDb = compositePeakDb(peakTarget, shelfTarget)
            // 只补一半，剩下的靠限压器按需处理
            preampTarget = -peakDb * PREAMP_COMPENSATION
            // 曲线全平（等于关闭音效）时完全不介入：那一刻必须比特级原样透传
            limiterEngaged = peakDb > 0.05
            if (!limiterEngaged) limiterGain = 1.0
        }
        refreshBypass()
    }

    /** 目标和当前值都落在全平，且限压器不参与，才能整段跳过计算 */
    private fun refreshBypass() {
        if (limiterEngaged) {
            isBypass = false
            return
        }
        for (i in peakTarget.indices) {
            if (peakTarget[i] != 0.0 || peakCurrent[i] != 0.0) {
                isBypass = false
                return
            }
        }
        isBypass = shelfTarget == 0.0 && shelfCurrent == 0.0 && preampLinear == 1.0
    }

    /**
     * 目标曲线在 20Hz~20kHz 上的最大增益（dB，≥0）。
     *
     * 取整条曲线叠加后的峰而不是单个频段的最大值：相邻频段会互相叠加，
     * 电子预设 60Hz +6dB 配 230Hz +3.5dB，实际在 100Hz 附近能到 +7dB 以上。
     */
    private fun compositePeakDb(gains: DoubleArray, shelfDb: Double): Double {
        var peak = 0.0
        for (freq in ANALYSIS_FREQS) {
            val w = 2.0 * PI * freq / sampleRate
            var magnitude = 1.0
            for (band in gains.indices) {
                analysisFilters[band].setPeaking(sampleRate, peakFreqs[band], PEAK_Q, gains[band])
                magnitude *= analysisFilters[band].magnitudeAt(w)
            }
            analysisFilters.last().setLowShelf(sampleRate, BASS_SHELF_HZ, shelfDb)
            magnitude *= analysisFilters.last().magnitudeAt(w)
            val db = 20.0 * log10(magnitude.coerceAtLeast(1e-6))
            if (db > peak) peak = db
        }
        return peak
    }

    /** 换歌/跳转：清掉滤波器状态，避免上一段音频的残留振铃被带进来 */
    fun resetState() {
        peakFilters.forEach { filters -> filters.forEach { it.resetState() } }
        shelfFilters.forEach { it.resetState() }
        // 延迟线也要清：不然会从上一段音频里漏出 2ms 的残音
        delayLines.forEach { it.fill(0.0) }
        delayIndex = 0
        // 刻意不清 initialized：清了等于每首歌开头都把整条曲线重新淡入一遍，
        // 换歌时会听到低频「慢慢涌上来」。曲线只在处理器第一次启动时从全平起步
    }

    /**
     * 原地处理交错排列的 PCM（大小在 -1..1 之间），[frameCount] 是帧数（含所有声道）。
     *
     * 所有增益都在这个循环里平滑推进，调用方不需要（也不应该）自己插值。
     */
    fun process(buffer: FloatArray, frameCount: Int) {
        // 全平直通：一个采样都不用碰。关闭音效时的开销从这里归零
        if (isBypass) return
        if (!initialized) {
            // 从全平开始：先把系数写成 0dB（等价于直通），再交给渐变往上追
            for (index in peakCurrent.indices) {
                peakCurrent[index] = 0.0
                applyPeaking(index, peakFreqs[index], 0.0)
            }
            shelfCurrent = 0.0
            applyShelf(BASS_SHELF_HZ, 0.0)
            // 预衰减同样从 0 起步，跟曲线一起淡入
            preampCurrent = 0.0
            preampLinear = 1.0
            initialized = true
        }

        var frame = 0
        while (frame < frameCount) {
            val blockFrames = minOf(BLOCK_FRAMES, frameCount - frame)
            advanceGains()
            for (f in frame until frame + blockFrames) {
                val base = f * channelCount
                var framePeak = 0.0
                for (ch in 0 until channelCount) {
                    var sample = buffer[base + ch].toDouble() * preampLinear
                    for (band in peakFilters.indices) {
                        sample = peakFilters[band][ch].process(sample)
                    }
                    sample = shelfFilters[ch].process(sample)
                    frameScratch[ch] = sample
                    val absolute = abs(sample)
                    if (absolute > framePeak) framePeak = absolute
                }
                if (limiterEngaged) {
                    // 需要多大就压多大（瞬时），恢复按 release 慢慢来
                    val desired = if (framePeak > LIMITER_THRESHOLD) {
                        LIMITER_THRESHOLD / framePeak
                    } else {
                        1.0
                    }
                    limiterGain = if (desired < limiterGain) {
                        desired
                    } else {
                        limiterGain + (desired - limiterGain) * limiterRelease
                    }
                    // 把当前帧存进延迟线，输出 2ms 前那一帧——它对应的峰值已经被上面处理过了
                    for (ch in 0 until channelCount) {
                        delayedFrame[ch] = delayLines[ch][delayIndex]
                        delayLines[ch][delayIndex] = frameScratch[ch]
                    }
                    delayIndex = (delayIndex + 1) % lookaheadFrames
                    for (ch in 0 until channelCount) frameScratch[ch] = delayedFrame[ch] * limiterGain
                }
                for (ch in 0 until channelCount) buffer[base + ch] = frameScratch[ch].toFloat()
            }
            frame += blockFrames
        }
    }

    private fun advanceGains() {
        for (band in peakTarget.indices) {
            val target = peakTarget[band]
            if (abs(target - peakCurrent[band]) < GAIN_EPSILON) continue
            peakCurrent[band] = slew(peakCurrent[band], target)
            applyPeaking(band, peakFreqs[band], peakCurrent[band])
        }
        if (abs(shelfTarget - shelfCurrent) >= GAIN_EPSILON) {
            shelfCurrent = slew(shelfCurrent, shelfTarget)
            applyShelf(BASS_SHELF_HZ, shelfCurrent)
        }
        if (abs(preampTarget - preampCurrent) >= GAIN_EPSILON) {
            preampCurrent = slew(preampCurrent, preampTarget)
            preampLinear = 10.0.pow(preampCurrent / 20.0)
        }
        // 增益真正走回全平之后才允许下次整段跳过
        refreshBypass()
    }

    private fun slew(current: Double, target: Double): Double {
        val next = current + (target - current).coerceIn(
            -MAX_SLEW_DB_PER_BLOCK,
            MAX_SLEW_DB_PER_BLOCK
        )
        return if (abs(target - next) < GAIN_EPSILON) target else next
    }

    private fun applyPeaking(band: Int, freqHz: Int, gainDb: Double) {
        for (ch in 0 until channelCount) {
            peakFilters[band][ch].setPeaking(sampleRate, freqHz, PEAK_Q, gainDb)
        }
    }

    private fun applyShelf(freqHz: Int, gainDb: Double) {
        for (ch in 0 until channelCount) {
            shelfFilters[ch].setLowShelf(sampleRate, freqHz, gainDb)
        }
    }
}
