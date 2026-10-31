package com.lin0721.linmusic.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEffectSettingsTest {

    @Test
    fun `解析合法曲线`() {
        assertEquals(listOf(100, -100, 0, 200, 300), AudioEffectSettings.parseBands("100,-100,0,200,300"))
    }

    @Test
    fun `曲线长度不符回落到全平`() {
        assertEquals(AudioEffectSettings.DEFAULT_BANDS, AudioEffectSettings.parseBands("100,200"))
    }

    @Test
    fun `脏数据回落到全平`() {
        assertEquals(AudioEffectSettings.DEFAULT_BANDS, AudioEffectSettings.parseBands("a,b,c,d,e"))
        assertEquals(AudioEffectSettings.DEFAULT_BANDS, AudioEffectSettings.parseBands(null))
    }

    @Test
    fun `烧写在参考频点上的曲线原样返回`() {
        val curve = AudioEffectSettings.levelsForPreset(1)
        assertEquals(curve, AudioEffectSettings.interpolateToBandFreqs(curve, AudioEffectSettings.REFERENCE_FREQS_HZ))
    }

    @Test
    fun `低于最低参考频点时取首个增益`() {
        val curve = listOf(500, 0, 0, 0, 0)
        val levels = AudioEffectSettings.interpolateToBandFreqs(curve, listOf(20, 40))
        assertEquals(listOf(500, 500), levels)
    }

    @Test
    fun `高于最高参考频点时取末个增益`() {
        val curve = listOf(0, 0, 0, 0, 400)
        val levels = AudioEffectSettings.interpolateToBandFreqs(curve, listOf(16000, 20000))
        assertEquals(listOf(400, 400), levels)
    }

    @Test
    fun `低音预设在下沉频段抬升、中频不动`() {
        val curve = AudioEffectSettings.levelsForPreset(5)
        val levels = AudioEffectSettings.interpolateToBandFreqs(
            curve,
            listOf(60, 230, 910, 3600, 14000)
        )
        assertTrue("60Hz 应抬升，实际 ${levels[0]}", levels[0] > 0)
        assertTrue("低频抬升应高于中频", levels[0] > levels[2])
    }

    @Test
    fun `未知预设回落到全平`() {
        assertEquals(AudioEffectSettings.DEFAULT_BANDS, AudioEffectSettings.levelsForPreset(99))
    }

    @Test
    fun `格式化与解析可往返`() {
        val bands = listOf(50, -50, 0, 250, -250)
        assertEquals(bands, AudioEffectSettings.parseBands(AudioEffectSettings.formatBands(bands)))
    }

    // ---- 预设表完整性与新增预设的走向 ----

    @Test
    fun `每个预设都有说明文案`() {
        AudioEffectSettings.PRESET_LABELS.indices.forEach { index ->
            val desc = AudioEffectSettings.descriptionForPreset(index)
            assertTrue("预设 $index 缺少说明", !desc.isNullOrBlank())
        }
    }

    @Test
    fun `自定义与未知下标没有说明`() {
        assertNull(AudioEffectSettings.descriptionForPreset(AudioEffectSettings.PRESET_CUSTOM))
        assertNull(AudioEffectSettings.descriptionForPreset(999))
    }

    @Test
    fun `所有预设曲线都在均衡器合法范围内`() {
        // Equalizer 的 level 单位是毫贝，硬件上限 ±1500；留余量到 1200 防止手滑写错量级
        AudioEffectSettings.PRESET_LABELS.indices.forEach { index ->
            AudioEffectSettings.levelsForPreset(index).forEach { level ->
                assertTrue("预设 $index 存在越界增益 $level", level in -1200..1200)
            }
        }
    }

    @Test
    fun `播客预设压低频抬中频`() {
        val levels = AudioEffectSettings.levelsForPreset(11)
        assertTrue("60Hz 应衰减，实际 ${levels[0]}", levels[0] < 0)
        assertTrue("910Hz 应抬升，实际 ${levels[2]}", levels[2] > 0)
        assertTrue("14kHz 应削齿音，实际 ${levels[4]}", levels[4] < 0)
    }

    @Test
    fun `夜间预设收敛高低两端`() {
        val levels = AudioEffectSettings.levelsForPreset(10)
        assertTrue("60Hz 应衰减，实际 ${levels[0]}", levels[0] < 0)
        assertTrue("14kHz 应衰减，实际 ${levels[4]}", levels[4] < 0)
        assertTrue("中频应保留，实际 ${levels[2]}", levels[2] > 0)
    }

    @Test
    fun `电子预设呈 V 形`() {
        val levels = AudioEffectSettings.levelsForPreset(7)
        assertTrue("低频应最高，实际 ${levels[0]} vs 中频 ${levels[2]}", levels[0] > levels[2])
        assertTrue("高频应高于中频，实际 ${levels[4]} vs 中频 ${levels[2]}", levels[4] > levels[2])
    }
}
