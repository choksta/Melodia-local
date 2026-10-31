package com.lin0721.linmusic.core.player

import kotlin.math.ln

/**
 * 音效预设与自定义频段曲线。
 *
 * 增益统一用毫贝（millibel）表示，100 = 1dB，与 Android `AudioEffect.Equalizer` 的
 * level 单位一致，避免 UI、存储、播放器三处反复换算。
 *
 * 各设备支持的频段数不同（常见 5 段，也有 8~10 段），因此预设按「参考频点 + 对数插值」
 * 映射到实际频段，而不是写死频段下标。
 */
object AudioEffectSettings {
    // 自定义预设：由滑杆产生，不在预设曲线表内
    const val PRESET_CUSTOM = -1

    // 低音增强拉满时对应的低架增益（dB）。界面上的 0~1000 会按比例映射到这里
    const val BASS_SHELF_MAX_DB = 12f
    const val DEFAULT_PRESET = 0

    // 预设顺序即界面顺序。
    // 注意：预设下标会直接写入 DataStore，因此新预设只能追加到末尾。
    // 一旦在中间插入或调整顺序，用户已保存的选择会静默指向另一个预设。
    val PRESET_LABELS = listOf(
        "关闭", "流行", "摇滚", "古典", "人声", "低音",
        "爵士", "电子", "嘻哈", "民谣", "夜间", "播客"
    )

    // 与 PRESET_LABELS 一一对应的一句话说明：光看名字分不清「流行」和「电子」差在哪
    val PRESET_DESCRIPTIONS = listOf(
        "不做任何调整",                 // 关闭
        "中频略抬，人声更靠前",         // 流行
        "两端抬起、中频后退，做出空间", // 摇滚
        "只抬高频，保留弦乐泛音",       // 古典
        "压掉低频，突出 230Hz–3.6kHz",  // 人声
        "低频大幅抬起，适合电子舞曲",   // 低音
        "低频温和、中频温暖，镲片通透", // 爵士
        "V 形曲线，低频冲击加高频空气感", // 电子
        "低频最厚，808 与鼓点更沉",     // 嘻哈
        "低频收紧，突出人声与吉他",     // 民谣
        "高低频都收敛，小音量不扰人",   // 夜间
        "切低频轰鸣，抬语音清晰度"      // 播客
    )

    // 五段参考频点：低频 / 中低 / 中 / 中高 / 高频
    val REFERENCE_FREQS_HZ = listOf(60, 230, 910, 3600, 14000)

    val DEFAULT_BANDS = listOf(0, 0, 0, 0, 0)

    // 各预设在这五个参考频点上的增益（毫贝）
    // 频点顺序：60 / 230 / 910 / 3600 / 14000 Hz
    private val PRESET_CURVES = listOf(
        listOf(0, 0, 0, 0, 0),              // 关闭：全平
        listOf(200, 100, 300, 100, 200),    // 流行：中频略突出
        listOf(500, 300, -100, 300, 500),   // 摇滚：两端抬升、中频后退
        listOf(0, 0, 0, 200, 400),          // 古典：只抬高频
        listOf(-200, 300, 400, 300, 0),     // 人声：突出 230~3.6k
        listOf(700, 500, 0, 0, -100),       // 低音：低频大幅抬升
        listOf(250, 200, 150, 150, 250),    // 爵士：温和抬升，不抢任何频段
        listOf(600, 350, -150, 200, 500),   // 电子：V 形，中频让位
        listOf(650, 450, 100, 150, 250),    // 嘻哈：低频最厚，高频只保清晰
        listOf(-150, 100, 300, 250, 150),   // 民谣：低频收紧，中频前置
        listOf(-250, -150, 150, -100, -400),// 夜间：只留中频，压掉两端
        listOf(-600, 200, 400, 350, -300)   // 播客：切低频、提清晰度、削齿音
    )

    fun levelsForPreset(preset: Int): List<Int> =
        PRESET_CURVES.getOrNull(preset) ?: DEFAULT_BANDS

    /** 取预设说明；自定义曲线与未知下标返回 null，由界面决定显示什么 */
    fun descriptionForPreset(preset: Int): String? = PRESET_DESCRIPTIONS.getOrNull(preset)

    // 三张表必须等长：漏改一处会让药丸、曲线、说明三者错位，且界面上看不出来
    init {
        require(PRESET_LABELS.size == PRESET_CURVES.size) {
            "预设名称 ${PRESET_LABELS.size} 项与曲线 ${PRESET_CURVES.size} 项不一致"
        }
        require(PRESET_LABELS.size == PRESET_DESCRIPTIONS.size) {
            "预设名称 ${PRESET_LABELS.size} 项与说明 ${PRESET_DESCRIPTIONS.size} 项不一致"
        }
        require(PRESET_CURVES.all { it.size == REFERENCE_FREQS_HZ.size }) {
            "每条预设曲线都必须覆盖 ${REFERENCE_FREQS_HZ.size} 个参考频点"
        }
    }

    /**
     * 把参考曲线按对数频率线性插值到设备实际频段。
     * 落在参考范围之外的频段取端点值，保证任何频段的设备都能得到合理曲线。
     */
    fun interpolateToBandFreqs(curve: List<Int>, bandFreqsHz: List<Int>): List<Int> {
        if (curve.isEmpty() || bandFreqsHz.isEmpty()) return List(bandFreqsHz.size) { 0 }
        val refLog = REFERENCE_FREQS_HZ.map { ln(it.toDouble()) }
        return bandFreqsHz.map { freq ->
            val log = ln(freq.coerceAtLeast(1).toDouble())
            when {
                log <= refLog.first() -> curve.first()
                log >= refLog.last() -> curve.last()
                else -> {
                    val upper = refLog.indexOfFirst { it >= log }
                    val lower = (upper - 1).coerceAtLeast(0)
                    val span = refLog[upper] - refLog[lower]
                    val ratio = if (span <= 0.0) 0.0 else (log - refLog[lower]) / span
                    (curve[lower] + (curve[upper] - curve[lower]) * ratio).toInt()
                }
            }
        }
    }

    fun formatBands(bands: List<Int>): String = bands.joinToString(",")

    /** 解析失败或长度不符时回落到全平，避免旧数据/脏数据把曲线画歪 */
    fun parseBands(raw: String?): List<Int> =
        raw?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == REFERENCE_FREQS_HZ.size }
            ?: DEFAULT_BANDS
}
