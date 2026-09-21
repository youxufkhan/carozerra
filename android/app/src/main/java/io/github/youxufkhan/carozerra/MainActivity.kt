package io.github.youxufkhan.carozerra

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : Activity() {

    private lateinit var repo: ClipRepository
    private lateinit var view: FaceplateView
    private lateinit var audio: AudioBridge
    private val poll = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollVolume = object : Runnable {
        override fun run() {
            // The car's own volume buttons move the stream behind our back.
            view.volumePercent = audio.refreshVolume()
            poll.postDelayed(this, 1000L)
        }
    }

    internal val faceplate: FaceplateView get() = view

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the dashboard blanks part-way through a clip.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // One repository for the whole app: the gallery and the view share its
        // LRU and its thumbnail cache.
        repo = ClipRepository(assets)
        view = FaceplateView(this, repo)
        audio = AudioBridge(this)
        view.volumePercent = audio.refreshVolume()
        view.onVolumeDrag = { delta ->
            audio.setVolumePercent(audio.volumePercent + Math.round(delta))
            view.volumePercent = audio.volumePercent
        }
        view.onControl = { hit ->
            when (hit.control) {
                Control.PRESET -> { view.selectPreset(hit.data); true }
                Control.SOURCE -> { view.cycleCategory(); true }
                Control.FUNCTION -> { view.glow = !view.glow; true }
                Control.EQ -> { view.glowIntensity = (view.glowIntensity + 1) % 3; true }
                Control.EQEX -> { view.scanlines = !view.scanlines; true }
                Control.TA -> { audio.toggleMute(); view.volumePercent = audio.volumePercent; true }
                Control.NAV_CENTER -> { audio.playPause(); true }
                Control.NAV -> {
                    when (hit.data) {
                        Geometry.NAV_UP -> view.fps += 2
                        Geometry.NAV_DOWN -> view.fps -= 2
                        Geometry.NAV_RIGHT -> audio.nextTrack()
                        Geometry.NAV_LEFT -> audio.previousTrack()
                    }
                    true
                }
                else -> false
            }
        }
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        poll.post(pollVolume)
    }

    override fun onPause() {
        poll.removeCallbacks(pollVolume)
        super.onPause()
    }
}
