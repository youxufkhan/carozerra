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
        setContentView(view)
    }
}
