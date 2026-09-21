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
    private lateinit var level: LevelSource
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
        level = LevelSource(audio)
        view.levelProvider = { level.level }
        view.availableProvider = { level.available }
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
                Control.DISPLAY -> { view.overlays.displayMode += 1; view.invalidate(); true }
                Control.TEXT -> {
                    view.overlays.textLine = !view.overlays.textLine
                    view.invalidate(); true
                }
                Control.EQ -> {
                    view.glowIntensity = (view.glowIntensity + 1) % 3
                    true
                }
                Control.EQEX -> {
                    view.scanlines = !view.scanlines
                    true
                }
                Control.TA -> { audio.toggleMute(); view.volumePercent = audio.volumePercent; true }
                Control.AUDIO -> {
                    if (!level.available) {
                        view.overlays.meters = false
                        view.flash("NO SIGNAL")
                    } else {
                        view.overlays.meters = !view.overlays.meters
                    }
                    true
                }
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
        startLevelSource()
    }

    override fun onPause() {
        level.stop()
        poll.removeCallbacks(pollVolume)
        super.onPause()
    }

    private fun startLevelSource() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        level.start()
        view.overlays.meters = level.available
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 1 &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) startLevelSource()
    }
}
