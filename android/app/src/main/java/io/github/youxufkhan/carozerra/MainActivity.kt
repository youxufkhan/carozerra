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
    private lateinit var gallery: GalleryOverlay
    internal lateinit var card: CardOverlay
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
        maybeRequestMicPermission()
        view.levelProvider = { level.level }
        view.availableProvider = { level.available }
        view.volumePercent = audio.refreshVolume()
        view.onVolumeDrag = { pct ->
            audio.setVolumePercent(Math.round(pct))
            view.volumePercent = audio.volumePercent
        }
        view.onKnobTap = {
            if (view.blackout) wake() else blackoutOn()
        }
        view.onKnobLongPress = { blackoutOn() }
        view.onBandLongPress = { finish() }
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
                Control.ENT -> { gallery.show(); true }
                Control.OPEN -> { card.show(); true }
                Control.BAND -> { bandPressed(); true }
                else -> false
            }
        }
        val root = android.widget.FrameLayout(this)
        root.addView(view)
        gallery = GalleryOverlay(this, repo) { name ->   // the same repository the view uses
            view.select(name)
        }
        root.addView(gallery)
        card = CardOverlay(this)
        root.addView(card)
        setContentView(root)

        // SelftestTest explicitly dismisses this before sampling the OEL -- if you
        // add another overlay that auto-shows at launch, update that test too.
        card.show()
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ card.hide() }, 5000L)
    }

    /**
     * Shown once, ever, before the system permission dialog: RECORD_AUDIO reads
     * this device's own playing audio for the level meters, not the microphone --
     * Android just gates both behind the same permission.
     */
    private fun maybeRequestMicPermission() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val prefs = getSharedPreferences("carozerra", MODE_PRIVATE)
        if (prefs.getBoolean("mic_rationale_shown", false)) return

        micRationaleDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Level meters need audio access")
            .setMessage(
                "The AUDIO button drives live level meters from whatever is " +
                    "actually playing on this device, the way a real head unit " +
                    "shows a live signal. Android requires the same RECORD_AUDIO " +
                    "permission for reading on-device playback as it does for the " +
                    "microphone -- this app never accesses microphone audio.\n\n" +
                    "You can skip this; the meters will just stay off."
            )
            .setPositiveButton("Continue") { _, _ ->
                prefs.edit().putBoolean("mic_rationale_shown", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean("mic_rationale_shown", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    // Dismissed explicitly rather than left to the system: an AlertDialog
    // outlives its Activity's window unless dismissed, which Android reports
    // as a leaked window if onDestroy runs while it's still showing (e.g. the
    // user backgrounds the app before tapping either button).
    private var micRationaleDialog: android.app.AlertDialog? = null

    override fun onDestroy() {
        micRationaleDialog?.dismiss()
        super.onDestroy()
    }

    private fun blackoutOn() {
        view.blackout = true
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
    }

    private fun wake() {
        view.blackout = false
        window.attributes = window.attributes.apply { screenBrightness = -1f }
    }

    private fun bandPressed() {
        when {
            gallery.isShowing -> gallery.hide()
            card.isShowing -> card.hide()
            else -> view.flash("HOLD TO EXIT")
        }
    }

    // The gallery and card overlays sit above FaceplateView and consume every
    // touch themselves (a thumbnail pick, or dismiss-on-any-tap) before
    // Geometry.hit ever runs — so BAND's own hitbox is otherwise unreachable
    // while either is open. Intercept at the activity level instead of
    // duplicating BAND-hitbox logic in both overlay classes.
    private var absorbingBandGesture = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            absorbingBandGesture = false
            if (gallery.isShowing || card.isShowing) {
                val (bx, by) = view.toBase(ev.x, ev.y).let { it[0] to it[1] }
                if (Geometry.hit(bx, by)?.control == Control.BAND) {
                    absorbingBandGesture = true
                }
            }
        }
        if (absorbingBandGesture) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                bandPressed()
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        poll.post(pollVolume)
        startLevelSourceIfGranted()
    }

    override fun onPause() {
        level.stop()
        poll.removeCallbacks(pollVolume)
        super.onPause()
    }

    private fun startLevelSourceIfGranted() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            level.start()
            view.overlays.meters = level.available
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 1 &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) startLevelSourceIfGranted()
    }
}
