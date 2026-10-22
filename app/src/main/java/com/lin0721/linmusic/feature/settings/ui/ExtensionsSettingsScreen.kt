package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import com.lin0721.linmusic.core.preferences.UiScale
import com.lin0721.linmusic.feature.player.ui.LyricCapsuleSlider
import com.lin0721.linmusic.core.ui.components.MelodiaDragHandle
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.LocalMelodiaWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing
import com.lin0721.linmusic.core.ui.theme.MelodiaWindowSizeClass
import com.lin0721.linmusic.core.ui.theme.SurfaceOverlay
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.lin0721.linmusic.core.ui.theme.SurfaceLight

// 主题模式的三档选项
private val ThemeModes = listOf(
    SettingsPreferences.THEME_MODE_SYSTEM to "跟随系统",
    SettingsPreferences.THEME_MODE_LIGHT to "浅色",
    SettingsPreferences.THEME_MODE_DARK to "深色"
)

// 明暗主题三档切换。选中态用品牌红填充，未选中态用当前主题的表面色，
// 因此浅色/深色下都不依赖硬编码底色
@Composable
private fun ThemeModeSelector(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeModes.forEach { (key, label) ->
            val selected = key == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else SurfaceLight)
                    .clickable { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private val PlayerPageModes = listOf(
    SettingsPreferences.PLAYER_PAGE_MODE_AUTO to "自动",
    SettingsPreferences.PLAYER_PAGE_MODE_FULLSCREEN to "全屏式",
    SettingsPreferences.PLAYER_PAGE_MODE_SIDE to "侧边小窗式"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsSettingsView(viewModel: SettingsViewModel) {
    val showLockscreen by viewModel.showLockscreen.collectAsStateWithLifecycle()
    val carMode by viewModel.carMode.collectAsStateWithLifecycle()
    val bottomBarLayout by viewModel.bottomBarLayout.collectAsStateWithLifecycle()
    val bottomBarIconOnly by viewModel.bottomBarIconOnly.collectAsStateWithLifecycle()
    val startInLibrary by viewModel.startInLibrary.collectAsStateWithLifecycle()
    val playerPageMode by viewModel.playerPageMode.collectAsStateWithLifecycle()
    val sidePlayerPinned by viewModel.sidePlayerPinned.collectAsStateWithLifecycle()
    val tabletImmersiveMode by viewModel.tabletImmersiveMode.collectAsStateWithLifecycle()
    val uiScalePercent by viewModel.uiScalePercent.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val uiAnimationsEnabled by viewModel.uiAnimationsEnabled.collectAsStateWithLifecycle()
    val isExpanded = LocalMelodiaWindowSizeClass.current == MelodiaWindowSizeClass.Expanded

    var showBottomBarSheet by remember { mutableStateOf(false) }
    var showPlayerModeSheet by remember { mutableStateOf(false) }
    val playerModeLabel = PlayerPageModes.firstOrNull { it.first == playerPageMode }?.second ?: "自动"
    val playerModeSubtitle = when (playerPageMode) {
        SettingsPreferences.PLAYER_PAGE_MODE_FULLSCREEN -> "全屏式：点击展开，返回直接收起"
        SettingsPreferences.PLAYER_PAGE_MODE_SIDE -> "侧边小窗式：播放页固定在右侧，与内容并排"
        else -> "自动：竖屏全屏式，横屏侧边小窗式"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(MelodiaSpacing.md),
            contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayInset.current + 16.dp)
        ) {
            item {
                SettingsGroupCard(SettingsSubMenu.EXTENSIONS.sectionTitles[0]) {
                    SettingsSwitchRow(
                        title = "启用系统锁屏显示",
                        subtitle = "在锁屏界面展示播放控制器与歌词面板",
                        checked = showLockscreen,
                        onCheckedChange = { viewModel.updateShowLockscreen(it) }
                    )
                }
            }

            item {
                SettingsGroupCard(SettingsSubMenu.EXTENSIONS.sectionTitles[1]) {
                    SettingsSwitchRow(
                        title = "车载模式蓝牙自动启动",
                        subtitle = "连接车载蓝牙设备时自动恢复媒体播放",
                        checked = carMode,
                        onCheckedChange = { viewModel.updateCarMode(it) }
                    )
                }
            }

            item {
                SettingsGroupCard(SettingsSubMenu.EXTENSIONS.sectionTitles[2]) {
                    val enabledItemsSummary = remember(bottomBarLayout) {
                        bottomBarLayout.filter { it.visible }.joinToString("、") { it.item.title }
                    }
                    SettingsRow(
                        title = "底栏自定义",
                        subtitle = "当前启用：$enabledItemsSummary",
                        onClick = { showBottomBarSheet = true }
                    )
                    HorizontalDivider(color = SurfaceOverlay.copy(alpha = 0.08f))
                    SettingsSwitchRow(
                        title = "启动默认打开音乐库",
                        subtitle = "冷启动时直接进入音乐库页面",
                        checked = startInLibrary,
                        onCheckedChange = { viewModel.updateStartInLibrary(it) }
                    )
                }
            }

            item {
                SettingsGroupCard(SettingsSubMenu.EXTENSIONS.sectionTitles[3]) {
                    SettingsRow(
                        title = "播放页模式：$playerModeLabel",
                        subtitle = playerModeSubtitle,
                        onClick = { showPlayerModeSheet = true }
                    )
                    if (playerPageMode != SettingsPreferences.PLAYER_PAGE_MODE_FULLSCREEN) {
                        HorizontalDivider(color = SurfaceOverlay.copy(alpha = 0.08f))
                        SettingsSwitchRow(
                            title = "侧边播放页钉住",
                            subtitle = "关闭后按返回键直接收起侧边播放页",
                            checked = sidePlayerPinned,
                            onCheckedChange = { viewModel.updateSidePlayerPinned(it) }
                        )
                    }
                    if (isExpanded) {
                        HorizontalDivider(color = SurfaceOverlay.copy(alpha = 0.08f))
                        SettingsSwitchRow(
                            title = "全局沉浸",
                            subtitle = "隐藏系统状态栏与导航栏，边缘滑动呼出",
                            checked = tabletImmersiveMode,
                            onCheckedChange = { viewModel.updateTabletImmersiveMode(it) }
                        )
                    }
                }
            }

            item {
                SettingsGroupCard(SettingsSubMenu.EXTENSIONS.sectionTitles[4]) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Text("主题", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                        Text(
                            text = "跟随系统时随系统深色模式自动切换",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        ThemeModeSelector(current = themeMode, onSelect = { viewModel.updateThemeMode(it) })
                    }
                    HorizontalDivider(color = SurfaceOverlay.copy(alpha = 0.08f))
                    // 松手后才应用（会重建界面）；拖动中不应用，避免滑块在手指下移位
                    var pendingPercent by remember { mutableStateOf<Int?>(null) }
                    LaunchedEffect(uiScalePercent) { pendingPercent = null }
                    val displayPercent = pendingPercent ?: uiScalePercent
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("界面缩放", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                            Text(
                                text = "$displayPercent%",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "整体缩放界面，松手后生效，界面会重新加载一次",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LyricCapsuleSlider(
                            value = displayPercent,
                            onValueChange = { pendingPercent = it },
                            valueRange = UiScale.MIN_PERCENT.toFloat()..UiScale.MAX_PERCENT.toFloat(),
                            startLabel = "${UiScale.MIN_PERCENT}%",
                            endLabel = "${UiScale.MAX_PERCENT}%",
                            onValueChangeFinished = {
                                pendingPercent?.let { viewModel.updateUiScalePercent(it) }
                            }
                        )
                    }
                    HorizontalDivider(color = SurfaceOverlay.copy(alpha = 0.08f))
                    SettingsSwitchRow(
                        title = "界面过渡动画",
                        subtitle = "关闭后切页、面板展开收起等直接切换，不影响歌词滚动",
                        checked = uiAnimationsEnabled,
                        onCheckedChange = { viewModel.updateUiAnimationsEnabled(it) }
                    )
                }
            }
        }

        if (showPlayerModeSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPlayerModeSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                        text = "选择播放页模式",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(bottom = MelodiaSpacing.md)
                    )
                    PlayerPageModes.forEach { (key, label) ->
                        val isSelected = playerPageMode == key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updatePlayerPageMode(key)
                                    showPlayerModeSheet = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp
                            )
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        if (showBottomBarSheet) {
            BottomBarCustomizationSheet(
                layout = bottomBarLayout,
                iconOnly = bottomBarIconOnly,
                onLayoutChange = { viewModel.updateBottomBarLayout(it) },
                onIconOnlyChange = { viewModel.updateBottomBarIconOnly(it) },
                onDismiss = { showBottomBarSheet = false }
            )
        }
    }
}
