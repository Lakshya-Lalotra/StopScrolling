package com.scrollstop.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat

private fun overlayParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

/**
 * Covers parts of another app's screen (the Reels tab button, the Explore grid, YouTube's Home feed)
 * so they can't be seen or tapped. The covers swallow touches.
 */
class HideOverlays(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val ctx = ContextThemeWrapper(svc, R.style.Theme_ScrollStop)
    private val views = ArrayList<Pair<TextView, Rect>>()
    var ownerPkg: String? = null
        private set

    val isShowing get() = views.isNotEmpty()

    fun show(pkg: String, hides: List<Hide>) {
        if (hides.isEmpty()) {
            clear()
            return
        }
        ownerPkg = pkg
        while (views.size > hides.size) remove(views.removeAt(views.size - 1).first)
        hides.forEachIndexed { i, h ->
            if (i < views.size) {
                val (v, old) = views[i]
                if (old != h.rect || v.text != (h.text ?: "")) {
                    style(v, h)
                    runCatching { wm.updateViewLayout(v, params(h.rect)) }
                    views[i] = v to Rect(h.rect)
                }
            } else {
                val v = TextView(ctx)
                style(v, h)
                if (runCatching { wm.addView(v, params(h.rect)) }.isSuccess) views += v to Rect(h.rect)
            }
        }
    }

    fun clear() {
        for ((v, _) in views) remove(v)
        views.clear()
        ownerPkg = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun style(v: TextView, h: Hide) {
        v.text = h.text ?: ""
        v.gravity = Gravity.CENTER
        v.setBackgroundResource(R.drawable.hide_bg)
        v.setTextColor(ContextCompat.getColor(ctx, R.color.hide_fg))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        v.setPadding(48, 48, 48, 48)
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        v.setOnTouchListener { _, _ -> true } // swallow taps on the covered button
    }

    private fun params(r: Rect) = overlayParams().apply {
        x = r.left
        y = r.top
        width = r.width().coerceAtLeast(1)
        height = r.height().coerceAtLeast(1)
    }

    private fun remove(v: View) {
        runCatching { wm.removeViewImmediate(v) }
    }
}

/** Full-screen reminder shown after something was blocked. */
class BlockScreen(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val ctx = ContextThemeWrapper(svc, R.style.Theme_ScrollStop)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val autoClose = Runnable { dismiss() }

    /** The app that was blocked; the screen stays up while that app or the home screen is in front. */
    var forPkg: String? = null
        private set
    val isShowing get() = view != null

    fun show(title: String, message: String, statsLine: String, pkg: String?, autoCloseMs: Long = 20_000) {
        dismiss()
        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_block, null)
        v.findViewById<TextView>(R.id.blkTitle).text = title
        v.findViewById<TextView>(R.id.blkMessage).text = message
        v.findViewById<TextView>(R.id.blkStats).text = statsLine
        v.findViewById<View>(R.id.blkClose).setOnClickListener { dismiss() }
        val lp = overlayParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) {
            view = v
            forPkg = pkg
            handler.postDelayed(autoClose, autoCloseMs)
        }
    }

    fun dismiss() {
        handler.removeCallbacks(autoClose)
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        forPkg = null
    }

    companion object {
        val QUOTES = listOf(
            "You closed the loop. Nice.",
            "The feed is endless. Your evening isn't.",
            "Nothing you'd miss is in there.",
            "Bored is fine. Bored is where ideas start.",
            "Look up. Breathe. Do one real thing.",
            "Your attention is worth more than an algorithm thinks.",
            "One less scroll, one more minute that's yours.",
            "Text a friend instead?",
            "Drink some water, stretch, then decide.",
            "Future you says thanks.",
        )
    }
}
