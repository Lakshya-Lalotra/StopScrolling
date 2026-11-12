package com.scrollstop.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.RequiresApi
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
 * so they can't be seen or tapped. The covers swallow touches and are painted in the app's own
 * background colour (see [ColorSampler]) so the button looks removed rather than boxed out.
 */
class HideOverlays(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val ctx = ContextThemeWrapper(svc, R.style.Theme_ScrollStop)
    private val views = ArrayList<Pair<TextView, Hide>>()
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
                if (old != h) {
                    style(v, h)
                    if (old.rect != h.rect) runCatching { wm.updateViewLayout(v, params(h.rect)) }
                    views[i] = v to h.copy(rect = Rect(h.rect))
                }
            } else {
                val v = TextView(ctx)
                style(v, h)
                if (runCatching { wm.addView(v, params(h.rect)) }.isSuccess) views += v to h.copy(rect = Rect(h.rect))
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
        val bg = h.color ?: ContextCompat.getColor(ctx, R.color.hide_bg)
        v.text = h.text ?: ""
        v.gravity = Gravity.CENTER
        v.setBackgroundColor(bg)
        v.setTextColor(if (Colors.isDark(bg)) 0xFF8A8A99.toInt() else 0xFF5F5F6E.toInt())
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

/**
 * Finds the background colour behind a tab button (Android 11+): takes one screenshot while the
 * cover is still see-through, reads a few pixels along the button's edges and keeps the result in
 * memory for that app and light/dark mode. The screenshot is never saved or sent anywhere.
 */
class ColorSampler(private val svc: AccessibilityService, private val onSampled: () -> Unit) {
    private val cache = HashMap<String, Int>()
    private val attempts = HashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var lastTry = 0L
    private var failures = 0

    /** False on Android 10 and older, or when screenshots keep failing; covers then use the default colour. */
    val canSample get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && failures < 6

    private fun night() =
        (svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun key(pkg: String, r: Rect) = "$pkg|${night()}|${r.width()}x${r.height()}"

    fun tabColor(pkg: String, r: Rect): Int? = cache[key(pkg, r)]

    /** Stops retrying a button after a few tries, so its cover falls back to the default colour. */
    fun worthTrying(pkg: String, r: Rect): Boolean = canSample && (attempts[key(pkg, r)] ?: 0) < 3

    /** Any colour already measured in this app (used for the bigger Explore / Home-feed covers). */
    fun appColor(pkg: String): Int? {
        val prefix = "$pkg|${night()}|"
        return cache.entries.firstOrNull { it.key.startsWith(prefix) }?.value
    }

    fun sample(pkg: String, rects: List<Rect>) {
        if (!canSample || busy || rects.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry < 1_500) return
        busy = true
        lastTry = now
        for (r in rects) key(pkg, r).let { attempts[it] = (attempts[it] ?: 0) + 1 }
        val copy = rects.map { Rect(it) }
        // Give the see-through covers a moment to be on screen, then capture.
        handler.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) capture(pkg, copy) else busy = false
        }, 150)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun capture(pkg: String, rects: List<Rect>) {
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val front = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
                            if (front == pkg) read(pkg, rects, result)
                            failures = 0
                        } catch (e: RuntimeException) {
                            failures++
                        } finally {
                            runCatching { result.hardwareBuffer.close() }
                            busy = false
                        }
                        onSampled()
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        failures++
                    }
                })
        } catch (e: RuntimeException) {
            busy = false
            failures++
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun read(pkg: String, rects: List<Rect>, result: AccessibilityService.ScreenshotResult) {
        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: return
        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
        hw.recycle()
        bmp ?: return
        try {
            for (r in rects) {
                val pts = Colors.borderPoints(r.left, r.top, r.right, r.bottom, inset = 4, perEdge = 8)
                val px = pts.filter { (x, y) -> x in 0 until bmp.width && y in 0 until bmp.height }
                    .map { (x, y) -> bmp.getPixel(x, y) }
                    .toIntArray()
                Colors.dominant(px)?.let { cache[key(pkg, r)] = it }
            }
        } finally {
            bmp.recycle()
        }
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
