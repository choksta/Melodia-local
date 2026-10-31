package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.player.AudioEffectSettings
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.ui.components.MelodiaDragHandle
import com.lin0721.linmusic.core.ui.components.MelodiaSwitch
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing
import kotlinx.coroutines.launch

/**
 * 播放页「音效调节」弹层。
 *
 * 把设置页里那套控件搬到手边：听歌时想给某首歌加/减低频，不用退出播放页去设置里翻。
 * 控件实现与设置页共用 [AudioEffectControls]，这里只负责读设置、写回，以及开关状态下
 * 「关掉时收起全部控件、只留开关」的呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEffectSheet(
    settingsPreferences: SettingsPreferences,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val enabled by settingsPreferences.audioEffectEnabled.collectAsStateWithLifecycle(initialValue = false)
    val preset by settingsPreferences.audioEffectPreset.collectAsStateWithLifecycle(initialValue = AudioEffectSettings.DEFAULT_PRESET)
    val bands by settingsPreferences.audioEffectBands.collectAsStateWithLifecycle(initialValue = AudioEffectSettings.DEFAULT_BANDS)
    val bassStrength by settingsPreferences.bassBoostStrength.collectAsStateWithLifecycle(initialValue = 0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        shape = BottomSheetShape,
        dragHandle = { MelodiaDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = MelodiaSpacing.lg, end = MelodiaSpacing.lg, bottom = MelodiaSpacing.lg)
        ) {
            Text(
                text = "音效调节",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "调整实时生效，随时可以再进来改",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "启用音效",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "开启后会自动关闭硬件音频解码",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                MelodiaSwitch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        scope.launch { settingsPreferences.saveAudioEffectEnabled(checked) }
                    }
                )
            }

            if (enabled) {
                AudioEffectControls(
                    preset = preset,
                    bands = bands,
                    bassStrength = bassStrength,
                    // 一打开就滚到当前选中的那一档，否则只看到「关闭/流行」，会以为没选上
                    autoScrollPreset = true,
                    onPresetSelected = { selected ->
                        scope.launch {
                            // 选预设时把曲线一起落盘，回到「自定义」时滑杆才是刚听到的那条
                            settingsPreferences.saveAudioEffectPreset(
                                selected,
                                AudioEffectSettings.levelsForPreset(selected)
                            )
                        }
                    },
                    onBandChanged = { index, level ->
                        scope.launch {
                            // 拖动任一频段即视为自定义：预设切到 CUSTOM，同时写入新曲线
                            val updated = bands.toMutableList()
                            if (index in updated.indices) {
                                updated[index] = level
                                settingsPreferences.saveAudioEffectPreset(
                                    AudioEffectSettings.PRESET_CUSTOM,
                                    updated
                                )
                            }
                        }
                    },
                    onBassChanged = { strength ->
                        scope.launch { settingsPreferences.saveBassBoostStrength(strength) }
                    }
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
