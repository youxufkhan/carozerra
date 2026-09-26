package io.github.youxufkhan.carozerra

import android.content.Context
import android.graphics.Bitmap
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Thumbnails decode off the main thread on bind; 83 eager decodes would stall launch. */
class GalleryOverlay(
    context: Context,
    private val repo: ClipRepository,
    private val onPick: (String) -> Unit,
) : FrameLayout(context) {

    private data class Row(val category: String, val clip: String)

    private val rows: List<Row> =
        ClipCatalog.categories.flatMap { c -> c.clips.map { Row(c.name, it) } }

    val isShowing: Boolean get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(0xE6080C10.toInt())
        visibility = View.GONE

        val list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = Adapter()
        }
        list.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (list.findChildViewUnder(e.x, e.y) == null) hide()
                    return false
                }
            })
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
        })
        addView(list, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setOnClickListener { hide() }
    }

    fun show() { visibility = View.VISIBLE }
    fun hide() { visibility = View.GONE }

    private inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val image: ImageView = root.getChildAt(0) as ImageView
        val label: TextView = root.getChildAt(1) as TextView
        var boundClip: String = ""
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(12, 12, 12, 12)
                addView(ImageView(parent.context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
                addView(TextView(parent.context).apply {
                    setTextColor(OEL_CYAN)
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
            }
            return Holder(root)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.boundClip = row.clip
            holder.label.text = "${row.clip.removeSuffix(".lkd")}\n${row.category}"
            holder.image.setImageBitmap(null)
            holder.root.setOnClickListener { onPick(row.clip); hide() }

            val target = row.clip
            Thread {
                val bmp: Bitmap = try {
                    repo.thumbnail(target)
                } catch (e: Throwable) {
                    android.util.Log.e("GalleryOverlay", "thumbnail failed for $target", e)
                    return@Thread
                }
                holder.image.post {
                    if (holder.boundClip == target) {
                        holder.image.setImageBitmap(bmp)
                    }
                }
            }.start()
        }
    }
}
