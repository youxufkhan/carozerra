package io.github.youxufkhan.carozerra

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Everything the app knows about audio. Volume is read back after every write
 * so the knob shows the system's quantised value, not the drag position.
 *
 * Transport is write-only by design: dispatchMediaKeyEvent needs no permission,
 * where MediaController would need a notification-listener grant. The app can
 * command playback but cannot read it, so nothing displays a play state.
 */
class AudioBridge(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val maxVolume: Int
        get() = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    var volumePercent: Int = 0
        private set

    fun refreshVolume(): Int {
        volumePercent =
            Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maxVolume)
        return volumePercent
    }

    fun setVolumePercent(pct: Int) {
        val index = Math.round(pct.coerceIn(0, 100) * maxVolume / 100f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        refreshVolume()
    }

    /** TA on the real unit interrupts audio; mute is its nearest honest analogue. */
    fun toggleMute() {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        refreshVolume()
    }

    val isMuted: Boolean get() = am.isStreamMute(AudioManager.STREAM_MUSIC)

    fun isMusicActive(): Boolean = am.isMusicActive

    private fun send(code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun playPause() = send(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun nextTrack() = send(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previousTrack() = send(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
}
