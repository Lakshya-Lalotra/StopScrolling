package com.scrollstop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Opens every tab in light and dark mode to make sure the screens build without crashing.
 * Set -Dscrollstop.screenshots=<dir> to also save PNGs of each screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UiSmokeTest {
    private val outDir = System.getProperty("scrollstop.screenshots")?.let { File(it).apply { mkdirs() } }

    @Test fun allScreensRender() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        seed(ctx)
        for (mode in listOf("light", "dark")) {
            if (mode == "dark") RuntimeEnvironment.setQualifiers("+night")
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val nav = activity.findViewById<BottomNavigationView>(R.id.bottomNav)
            for ((id, name) in listOf(
                R.id.nav_home to "home", R.id.nav_block to "block",
                R.id.nav_stats to "stats", R.id.nav_settings to "settings",
            )) {
                nav.selectedItemId = id
                shadowOf(Looper.getMainLooper()).idle()
                val page = activity.findViewById<LinearLayout>(R.id.page)
                assertTrue("$name is empty", page.childCount > 2)
                save(fullPage(activity.findViewById(R.id.root), page), "${mode}_$name")
            }
            // Instagram / YouTube inside ScrollStop open without crashing
            for (site in WebSite.entries) {
                val web = Robolectric.buildActivity(WebAppActivity::class.java, WebAppActivity.intent(activity, site))
                    .setup().get()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(!web.isFinishing)
                // the ⚙ settings panel opens and has switches
                val gear = findButton(web.window.decorView, "⚙")
                gear!!.performClick()
                shadowOf(Looper.getMainLooper()).idle()
                val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                assertTrue(sheet != null && sheet.isShowing)
                if (site == WebSite.INSTAGRAM) {
                    val content = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    save(render(content, activity.window.decorView.width, 0,
                        MaterialColors.getColor(content, android.R.attr.colorBackground)), "${mode}_ig_settings")
                }
                sheet.dismiss()
                shadowOf(Looper.getMainLooper()).idle()
            }
            // the full-screen block reminder shown over other apps
            val themed = ContextThemeWrapper(activity, R.style.Theme_ScrollStop)
            val block = LayoutInflater.from(themed).inflate(R.layout.overlay_block, null)
            block.findViewById<TextView>(R.id.blkTitle).text = "Instagram Reels blocked"
            block.findViewById<TextView>(R.id.blkMessage).text = BlockScreen.QUOTES.first()
            block.findViewById<TextView>(R.id.blkStats).text = "7 blocked today · about 21m saved"
            save(render(block, activity.window.decorView.width, activity.window.decorView.height, null), "${mode}_blockscreen")
        }
    }

    private fun findButton(v: View, text: String): View? {
        if (v is TextView && v.text?.toString() == text) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findButton(v.getChildAt(i), text)?.let { return it }
        return null
    }

    /** Puts in some numbers and settings so the screens look like they would after a few days of use. */
    private fun seed(ctx: Context) {
        val stats = Stats.get(ctx)
        stats.add(Stats.BLOCKS, 7)
        stats.add(Stats.BLOCKS, 120, Stats.ALL)
        stats.add(Stats.blocks("ig"), 5)
        stats.add(Stats.shortCount(Platform.INSTAGRAM), 12)
        stats.add(Stats.shortMs(Platform.INSTAGRAM), 6 * 60_000L)
        stats.add(Stats.shortCount(Platform.YOUTUBE), 4)
        stats.add(Stats.shortMs(Platform.YOUTUBE), 3 * 60_000L)
        for (d in 0..6) {
            stats.add(Stats.appMs("com.instagram.android"), (20 + d * 9) * 60_000L, stats.day(d))
            stats.add(Stats.appMs("com.google.android.youtube"), (35 - d * 3) * 60_000L, stats.day(d))
            stats.add(Stats.BLOCKS, (d + 2).toLong(), stats.day(d))
        }
        val prefs = Prefs.get(ctx)
        prefs.setAllowance(Platform.YOUTUBE, Allowance(10, 20))
        prefs.appLimits = mapOf("com.android.chrome" to 45)
        prefs.schedule = prefs.schedule.copy(enabled = true)
    }

    private fun fullPage(root: View, page: LinearLayout): Bitmap {
        val w = root.width.coerceAtLeast(1)
        val bg = MaterialColors.getColor(root, android.R.attr.colorBackground)
        return render(page, w, 0, bg)
    }

    /** Draws [v] at width [w]; height 0 means "as tall as it wants". */
    private fun render(v: View, w: Int, h: Int, bg: Int?): Bitmap {
        val hs = if (h > 0) View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hs)
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth.coerceAtLeast(1), v.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        bg?.let { c.drawColor(it) }
        v.draw(c)
        return bmp
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = outDir ?: return
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
