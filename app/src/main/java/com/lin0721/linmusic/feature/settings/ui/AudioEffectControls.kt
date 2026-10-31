package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.core.player.AudioEffectSettings
import com.lin0721.linmusic.core.ui.components.FilterChipsRow
import com.lin0721.linmusic.feature.player.ui.LyricCapsuleSlider

/**
 * 音效调节的全部控件：预设药丸 + 五段滑杆 + 低音增强。
 *
 * 设置页与播放页弹层共用这一份，避免两处各写一套、以后改一处漏一处。
 * 组件只负责展示与回调，状态读取和落盘由调用方决定。
 */
@Composable
internal fun AudioEffectControls(
    preset: Int,
    bands: List<Int>,
    bassStrength: Int,
    onPresetSelected: (Int) -> Unit,
    onBandChanged: (index: Int, level: Int) -> Unit,
    onBassChanged: (strength: Int) -> Unit,
    modifier: Modifier = Modifier,
    // 弹层场景用 true：一打开就要能看见当前选的是哪一档
    autoScrollPreset: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("预设", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            // 一屏只放得下 5 个，不提示的话用户不知道右边还有 7 个
            Text(
                text = "左右滑动 · 共 ${AudioEffectSettings.PRESET_LABELS.size} 档",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
        FilterChipsRow(
            items = AudioEffectSettings.PRESET_LABELS,
            // 自定义曲线不落在任何预设上，传 -1 让药丸全部不选中
            selectedIndex = preset,
            onSelected = onPresetSelected,
            modifier = Modifier.fillMaxWidth(),
            autoScrollToSelected = autoScrollPreset
        )
        // 预设名字看不出曲线差别，下面这行说明当前选中的是什么走向
        Text(
            text = AudioEffectSettings.descriptionForPreset(preset)
                ?: "自定义曲线（拖动任一频段得到）",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, start = 20.dp, end = 20.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        bands.forEachIndexed { index, level ->
            // 拖动过程只更新本地状态，松手才落盘，避免拖一次写几十次 DataStore
            val dragLevel = remember(index, level) { mutableIntStateOf(level) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bandLabel(AudioEffectSettings.REFERENCE_FREQS_HZ.getOrElse(index) { 0 }),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Text(
                    text = gainLabel(dragLevel.intValue),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            LyricCapsuleSlider(
                value = dragLevel.intValue,
                onValueChange = { dragLevel.intValue = it },
                valueRange = -1200f..1200f,
                startLabel = "-12",
                endLabel = "+12",
                onValueChangeFinished = { onBandChanged(index, dragLevel.intValue) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("低音增强", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(
                text = if (bassStrength == 0) "关闭" else "${bassStrength / 10}%",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        val dragBass = remember(bassStrength) { mutableIntStateOf(bassStrength) }
        LyricCapsuleSlider(
            value = dragBass.intValue,
            onValueChange = { dragBass.intValue = it },
            valueRange = 0f..1000f,
            startLabel = "关",
            endLabel = "强",
            onValueChangeFinished = { onBassChanged(dragBass.intValue) }
        )
    }
}

// 频段标签：1kHz 以上用 kHz，避免 14000Hz 这种读起来费劲的写法
internal fun bandLabel(hz: Int): String =
    if (hz >= 1000) "${hz / 1000f}kHz" else "${hz}Hz"

// 毫贝转 dB 展示，保留一位小数并带正号
internal fun gainLabel(millibel: Int): String {
    val db = millibel / 100f
    val sign = if (db > 0f) "+" else ""
    return "$sign${(db * 10).toInt() / 10f} dB"
}
