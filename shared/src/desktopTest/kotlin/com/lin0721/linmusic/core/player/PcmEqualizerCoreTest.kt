package com.lin0721.linmusic.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * PCM 均衡器的频响验证：喂正弦波、量输出幅度，比对理论增益。
 *
 * 这几条断言的意义在于——DSP 写错时听感可能只是「怪」而不是「坏」，靠耳朵很难定位；
 * 而 biquad 系数错一个符号、Q 写反、归一化忘了除 a0，这里都会立刻测出来。
 */
class PcmEqualizerCoreTest {

    private val sampleRate = 48000

    /** 生成一段指定频率的正弦（单声道），幅度 [amplitude] */
    private fun sine(freqHz: Double, frames: Int, amplitude: Float = 0.2f): FloatArray =
        FloatArray(frames) { i ->
            (amplitude * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
        }

    /** 取最后一段的峰值，避开渐变未走完的前半段 */
    private fun tailPeak(buffer: FloatArray, fromRatio: Double = 0.75): Double {
        val start = (buffer.size * fromRatio).toInt()
        var peak = 0.0
        for (i in start until buffer.size) peak = maxOf(peak, abs(buffer[i].toDouble()))
        return peak
    }

    private fun run(core: PcmEqualizerCore, signal: FloatArray): FloatArray {
        val out = signal.copyOf()
        core.process(out, out.size)
        return out
    }

    @Test
    fun `全平曲线是直通`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(0f, 0f, 0f, 0f, 0f), 0f)
        val input = sine(1000.0, 48000)
        val output = run(core, input)
        for (i in input.indices) {
            assertEquals("第 $i 个采样应当原样通过", input[i].toDouble(), output[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun `60Hz 提升 6dB 时相对 1kHz 高约一倍`() {
        // 预衰减会把整体电平按曲线峰值压回去，所以只能比「频段之间」的相对关系，
        // 不能再断言绝对电平翻倍——绝对电平被压回原位正是防削顶想要的结果
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        val low = tailPeak(run(core, sine(60.0, sampleRate)))
        val reference = tailPeak(run(PcmEqualizerCore(sampleRate, 1).also {
            it.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        }, sine(1000.0, sampleRate)))
        assertEquals("60Hz 应比 1kHz 高约 6dB", 2.0, low / reference, 0.2)
    }

    @Test
    fun `只抬 60Hz 时 1kHz 被预衰减压低而不是被抬高`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        val peak = tailPeak(run(core, sine(1000.0, sampleRate)))
        assertTrue("1kHz 不该被抬，实测 $peak", peak < 0.2)
        assertTrue("预衰减约 3dB，1kHz 应明显低于原电平，实测 $peak", peak > 0.2 * 0.6)
    }

    @Test
    fun `5 段全部 -6dB 时整体显著衰减`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(-6f, -6f, -6f, -6f, -6f), 0f)
        val peak = tailPeak(run(core, sine(1000.0, sampleRate)))
        // 相邻峰值段在 910Hz 和 3.6kHz 上都会作用，1kHz 处合计约 -7dB 上下
        assertTrue("输出应明显小于输入，实测 $peak", peak < 0.2 * 0.55)
        assertTrue("但不应被压到几乎没有，实测 $peak", peak > 0.2 * 0.25)
    }

    @Test
    fun `低音增强只影响低频`() {
        fun peakAt(freq: Double, bassDb: Float): Double {
            val core = PcmEqualizerCore(sampleRate, 1)
            core.setTarget(floatArrayOf(0f, 0f, 0f, 0f, 0f), bassDb)
            return tailPeak(run(core, sine(freq, sampleRate)))
        }
        val low = peakAt(60.0, 12f)
        val high = peakAt(5000.0, 12f)
        val lowFlat = peakAt(60.0, 0f)
        val highFlat = peakAt(5000.0, 0f)
        // 架子拉满时，60Hz 相对 5kHz 的比值应明显变大（低架只能抬到 100Hz 以下）
        assertTrue("60Hz 相对 5kHz 应被抬高，实测 ${low / high}", low / high > (lowFlat / highFlat) * 1.8)
        // 而 5kHz 本身不该被低架改变：它只会被整体预衰减（12dB 峰值只补一半 = -6dB）
        assertEquals("5kHz 只应被预衰减压低，不该被低架动到", 0.5, high / highFlat, 0.05)
    }

    @Test
    fun `增益是渐变到位而不是一步跳过去`() {
        // 用 1kHz 观察：电子曲线在 1kHz 附近几乎不抬不削，电平变化主要来自预衰减，
        // 正好能把「预衰减是渐变过去的」这件事单独测出来
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        // 只给 10ms：按 40dB/s 的斜率最多走 0.4dB，不可能已经压到位
        val early = tailPeak(run(core, sine(1000.0, 480)))
        assertTrue("10ms 内不该已经压下去，实测 $early", early > 0.2 * 0.95)

        // 再给 500ms，应当已经压到约 -6dB
        val later = tailPeak(run(core, sine(1000.0, 24000)))
        assertTrue("500ms 后应压到约 -3dB，实测 $later", later < 0.2 * 0.8)
    }

    @Test
    fun `立体声两个声道独立处理`() {        val core = PcmEqualizerCore(sampleRate, 2)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        val frames = sampleRate
        val interleaved = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (0.2 * sin(2.0 * PI * 60.0 * i / sampleRate)).toFloat()
            interleaved[i * 2] = v
            interleaved[i * 2 + 1] = -v
        }
        core.process(interleaved, frames)
        val left = FloatArray(frames) { interleaved[it * 2] }
        val right = FloatArray(frames) { interleaved[it * 2 + 1] }
        val leftPeak = tailPeak(left)
        val rightPeak = tailPeak(right)
        assertEquals("左右声道增益应一致", leftPeak, rightPeak, 0.001)
        // 60Hz 抬 6dB、预衰减补一半（-3dB），净效果约 1.4 倍
        assertEquals("两端各自处理且互不影响", 0.2 * 1.995 * 0.7, leftPeak, 0.02)
    }

    /**
     * 破音回归测试。
     *
     * 母带本来就贴着满刻度，电子预设 60Hz 要 +6dB、100Hz 附近叠加后 +7dB，
     * 如果没有预衰减，0.95 的正弦进去会算到 1.9 直接削顶——听感就是「破音、糊」。
     */
    @Test
    fun `电子曲线不会把接近满刻度的信号推过 0dBFS`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 3.5f, -1.5f, 2f, 5f), 0f)
        val output = run(core, sine(60.0, sampleRate, amplitude = 0.95f))
        val peak = tailPeak(output)
        assertTrue("输出不应削顶，实测峰值 $peak", peak <= 1.0)
        assertTrue("会被预衰减压回满刻度以内，不该几乎没声，实测 $peak", peak > 0.6)
    }

    @Test
    fun `低音增强拉满时同样不削顶`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(0f, 0f, 0f, 0f, 0f), 12f)
        val peak = tailPeak(run(core, sine(60.0, sampleRate, amplitude = 0.95f)))
        assertTrue("输出不应削顶，实测峰值 $peak", peak <= 1.0)
    }

    @Test
    fun `没顶到天花板时不压缩，保住响度`() {
        // 0.5 幅度进、抬 6dB、只补一半预衰减 → 约 0.70，离 0.92 阈值还远，不该被压
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        val peak = tailPeak(run(core, sine(60.0, sampleRate, amplitude = 0.5f)))
        assertEquals("不该动它，实测 $peak", 0.5 * 1.995 * 0.7, peak, 0.06)
    }

    @Test
    fun `快溢出时压回阈值附近而不是硬削`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        val peak = tailPeak(run(core, sine(60.0, sampleRate, amplitude = 0.95f)))
        // 不加限压会是 1.32（硬削成 1.0）；限压后应贴着 0.92 而不是被压得很小
        assertTrue("应压到阈值附近，实测 $peak", peak in 0.85..1.0)
    }

    @Test
    fun `全平曲线不引入预衰减`() {
        // 预衰减只该补偿抬升，曲线全平时不能白白把音量压下去
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(0f, 0f, 0f, 0f, 0f), 0f)
        val peak = tailPeak(run(core, sine(1000.0, sampleRate, amplitude = 0.5f)))
        assertEquals("全平时应当原样通过", 0.5, peak, 0.005)
    }

    @Test
    fun `纯衰减曲线不需要预衰减`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(-6f, -6f, -6f, -6f, -6f), 0f)
        val peak = tailPeak(run(core, sine(1000.0, sampleRate, amplitude = 0.5f)))
        // 只有衰减时不该再额外压音量：1kHz 处约 -6dB 上下
        assertTrue("不该被压得比曲线本身更低，实测 $peak", peak > 0.5 * 0.4)
    }

    @Test
    fun `关闭音效时整段旁路，一个采样都不碰`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(0f, 0f, 0f, 0f, 0f), 0f)
        assertTrue("全平曲线应当直接旁路", core.isBypass)
        // 用极值信号：一旦被处理过就看得出来
        val input = floatArrayOf(0.9f, -0.9f, 0.5f, -0.5f, 1f, -1f)
        val output = run(core, input)
        for (i in input.indices) {
            assertEquals("旁路时第 $i 个采样必须原样保留", input[i].toDouble(), output[i].toDouble(), 0.0)
        }
    }

    @Test
    fun `从曲线渐变回全平之后才允许旁路`() {
        val core = PcmEqualizerCore(sampleRate, 1)
        core.setTarget(floatArrayOf(6f, 0f, 0f, 0f, 0f), 0f)
        run(core, sine(60.0, sampleRate))
        assertTrue("有曲线在生效时不能旁路", !core.isBypass)

        core.setTarget(floatArrayOf(0f, 0f, 0f, 0f, 0f), 0f)
        // 目标已经回全平，但增益还在往下走：这时候旁路会在中途留下一个台阶
        assertTrue("渐变途中不能旁路", !core.isBypass)

        run(core, sine(60.0, sampleRate * 2))
        assertTrue("增益走回全平之后应当旁路", core.isBypass)
    }
}
