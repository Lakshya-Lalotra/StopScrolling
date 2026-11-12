package com.scrollstop.app

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the layout of the screen in front as text: one line per element with its view ID, type,
 * label and position. Used to tune detection when an app update moves things around.
 */
object LayoutDump {
    fun write(ctx: Context, root: AccessibilityNodeInfo, screen: Rect): File {
        val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "screen-layout.txt")
        val sb = StringBuilder()
        val ver = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(root.packageName.toString(), 0).versionName
        }.getOrNull()
        sb.append("ScrollStop ${BuildConfig.VERSION_NAME} layout capture\n")
        sb.append("App: ${root.packageName} $ver · Android ${Build.VERSION.RELEASE} · screen ${screen.width()}x${screen.height()}\n")
        sb.append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n\n")
        var count = 0
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count++ > 4000) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName?.substringAfter(":id/")
            val cls = n.className?.toString()?.substringAfterLast('.')
            val text = n.text?.toString()?.replace('\n', ' ')?.take(60)
            val desc = n.contentDescription?.toString()?.replace('\n', ' ')?.take(60)
            val flags = buildString {
                if (n.isSelected) append(" selected")
                if (n.isClickable) append(" click")
                if (n.isScrollable) append(" scroll")
                if (!n.isVisibleToUser) append(" hidden")
            }
            sb.append("  ".repeat(depth.coerceAtMost(30)))
                .append(cls ?: "?")
                .append(id?.let { " #$it" } ?: "")
                .append(text?.let { " \"$it\"" } ?: "")
                .append(desc?.let { " [$it]" } ?: "")
                .append(" ${b.left},${b.top}-${b.right},${b.bottom}")
                .append(flags)
                .append('\n')
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (e: RuntimeException) {
                    null
                } ?: continue
                visit(c, depth + 1)
            }
        }
        visit(root, 0)
        file.writeText(sb.toString())
        return file
    }
}
