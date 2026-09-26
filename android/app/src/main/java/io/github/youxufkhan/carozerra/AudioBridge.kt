package io.github.youxufkhan.carozerra

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Visualizer
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

/**
 * Reads the output mix through Visualizer(0). Whether the platform allows that
 * depends on the playback-capture policy of whatever is playing, so the source
 * proves itself: if the waveform stays flat while music is active, capture is
 * being denied and the source marks itself unavailable.
 */
class LevelSource(private val bridge: AudioBridge) {

    var available: Boolean = false
        private set

    /** 0..1, fast attack and slow decay so the bars fall naturally. */
    var level: Float = 0f
        private set

    private var vis: Visualizer? = null
    private var flatSince = 0L

    fun start(): Boolean {
        stop()
        return try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        z: Visualizer?, waveform: ByteArray, rate: Int
                    ) = consume(waveform)

                    override fun onFftDataCapture(z: Visualizer?, fft: ByteArray, rate: Int) = Unit
                },
                Visualizer.getMaxCaptureRate() / 2, true, false
            )
            v.enabled = true
            vis = v
            available = true
            true
        } catch (t: Throwable) {
            // Denied permission, a ROM without the effect, or another app holding
            // the session all land here. None of them is worth crashing over.
            available = false
            false
        }
    }

    fun stop() {
        try { vis?.enabled = false; vis?.release() } catch (_: Throwable) {}
        vis = null
        level = 0f
    }

    private fun consume(waveform: ByteArray) {
        var peak = 0
        var sum = 0.0
        for (b in waveform) {
            val d = (b.toInt() and 0xFF) - 128
            if (kotlin.math.abs(d) > peak) peak = kotlin.math.abs(d)
            sum += (d * d).toDouble()
        }
        val rms = (Math.sqrt(sum / waveform.size) / 128.0).toFloat().coerceIn(0f, 1f)
        level = if (rms > level) rms else level * 0.85f

        // Flat for 3s while music is playing means capture is being denied.
        val now = android.os.SystemClock.elapsedRealtime()
        if (peak < 2 && bridge.isMusicActive()) {
            if (flatSince == 0L) flatSince = now
            if (now - flatSince > 3000L) { stop(); available = false }
        } else {
            flatSince = 0L
        }
    }
}
