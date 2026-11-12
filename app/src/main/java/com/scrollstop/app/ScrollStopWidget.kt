package com.scrollstop.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: today's social time and blocks, plus quick ways into the focused apps. */
class ScrollStopWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(ctx))
    }

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(ctx, ScrollStopWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val v = views(ctx)
            for (id in ids) runCatching { manager.updateAppWidget(id, v) }
        }

        private fun views(ctx: Context): RemoteViews {
            val prefs = Prefs.get(ctx)
            val stats = Stats.get(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val status = when {
                !ServiceState.isEnabled(ctx) -> "🔴 Off"
                prefs.isSleeping() -> "😴 Sleep mode"
                prefs.isPaused() -> "⏸ On a break"
                prefs.isLocked() -> "🔒 Strict"
                else -> "🟢 On"
            }
            val appMs = stats.appTimeOn(stats.day()).values.sum()
            v.setTextViewText(R.id.wStatus, status)
            v.setTextViewText(R.id.wTime, Fmt.duration(appMs))
            v.setTextViewText(R.id.wDetail, "${stats.get(Stats.BLOCKS)} blocked today")
            v.setOnClickPendingIntent(R.id.wRoot, activity(ctx, 0, Intent(ctx, MainActivity::class.java)))
            v.setOnClickPendingIntent(R.id.wInstagram, activity(ctx, 1, WebAppActivity.intent(ctx, WebSite.INSTAGRAM)))
            v.setOnClickPendingIntent(R.id.wYoutube, activity(ctx, 2, WebAppActivity.intent(ctx, WebSite.YOUTUBE)))
            return v
        }

        private fun activity(ctx: Context, code: Int, i: Intent): PendingIntent =
            PendingIntent.getActivity(
                ctx, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
