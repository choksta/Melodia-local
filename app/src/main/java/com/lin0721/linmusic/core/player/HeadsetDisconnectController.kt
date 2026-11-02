package com.lin0721.linmusic.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.preferences.SettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "HeadsetDisconnect"

// 两条信号可能在同一瞬间先后到达（拔有线耳机时系统既发广播又移除设备），去重窗口
private const val PAUSE_DEDUPE_WINDOW_MS = 600L

/**
 * 拔出耳机 / 蓝牙耳机断开时自动暂停。
 *
 * 为什么两条信号都要接：
 * - [AudioManager.ACTION_AUDIO_BECOMING_NOISY] 是有线拔出、蓝牙断开的系统标准广播，最可靠；
 * - 但部分机型在「蓝牙耳机被另一台设备抢走」「耳机盒没电瞬间掉线」时并不发这条广播，
 *   只能靠 [AudioDeviceCallback] 观察输出设备列表变化兜底。
 *
 * 为什么不用 ExoPlayer 自带的 `setHandleAudioBecomingNoisy(true)`：
 * 它只暂停被调用的那台底层播放器，外面那层 [CrossfadePlayer] 的对外状态不会同步，
 * UI 会停在「正在播放」而声音已经没了；交叉淡化期间还会漏掉另一台正在出声的播放器。
 * 这里统一走 `sessionPlayer.pause()`，两条路径都经过同一套状态机。
 */
class HeadsetDisconnectController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsPreferences: SettingsPreferences,
    private val onPauseRequested: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isEnabled = true

    private var isRegistered = false
    private var lastPauseAt = 0L

    // 会让人「私密收听」的输出类型：这些设备断开后继续播放就会直接外放
    private val privateOutputTypes: Set<Int> = buildSet {
        add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        add(AudioDeviceInfo.TYPE_USB_HEADSET)
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        add(AudioDeviceInfo.TYPE_HEARING_AID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addAll(bleOutputTypes())
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                requestPause("AUDIO_BECOMING_NOISY")
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            // 移除的不是收听设备（比如拔了个 U 盘）就跟播放无关
            if (removedDevices.none { it.type in privateOutputTypes }) return
            // 还有别的私密设备在，说明只是切换（耳机 A → 耳机 B），不该打断
            if (hasPrivateOutput()) return
            requestPause("外接输出设备已移除")
        }
    }

    fun start() {
        scope.launch {
            settingsPreferences.pauseOnHeadsetDisconnect.collect { enabled ->
                isEnabled = enabled
                if (enabled) register() else unregister()
            }
        }
    }

    fun release() {
        unregister()
    }

    private fun register() {
        if (isRegistered) return
        try {
            ContextCompat.registerReceiver(
                context,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_EXPORTED
            )
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
            isRegistered = true
            AppLogger.i(TAG, "已开启耳机断开暂停监听")
        } catch (e: Exception) {
            AppLogger.w(TAG, "注册耳机断开监听失败", e)
        }
    }

    private fun unregister() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (e: Exception) {
            AppLogger.w(TAG, "注销 becomingNoisyReceiver 失败", e)
        }
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
            AppLogger.w(TAG, "注销 AudioDeviceCallback 失败", e)
        }
        isRegistered = false
    }

    private fun hasPrivateOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in privateOutputTypes }

    private fun requestPause(reason: String) {
        if (!isEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPauseAt < PAUSE_DEDUPE_WINDOW_MS) return
        lastPauseAt = now
        AppLogger.i(TAG, "检测到$reason，暂停播放避免外放")
        onPauseRequested()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun bleOutputTypes(): Set<Int> = setOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER
    )
}
