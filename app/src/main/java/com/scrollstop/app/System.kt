package com.scrollstop.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Checks on the accessibility service and phone settings. */
object ServiceState {
    fun isEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, BlockerService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (s in splitter) if (ComponentName.unflattenFromString(s) == expected) return true
        return false
    }

    fun batteryUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun notificationsAllowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun accessibilitySettings() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appInfo(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun batteryRequest(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun batterySettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

/** Every 30 minutes: if the blocker was switched off (Android does this after some updates), say so. */
class ServiceCheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        if (prefs.everEnabled && !ServiceState.isEnabled(ctx) && now - prefs.lastOffWarning > 3 * 3_600_000L) {
            if (Notifier.blockerOff(ctx)) prefs.lastOffWarning = now
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ServiceCheckWorker>(30, TimeUnit.MINUTES).build()
            try {
                WorkManager.getInstance(ctx)
                    .enqueueUniquePeriodicWork("service-check", ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (e: IllegalStateException) {
                // WorkManager not ready (rare); the reminder is a nice-to-have, never crash for it
            }
        }
    }
}

object Notifier {
    private const val CHANNEL = "status"
    private const val ID_OFF = 1

    /** Returns true if the notification was posted. */
    fun blockerOff(ctx: Context): Boolean {
        if (!ServiceState.notificationsAllowed(ctx)) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Blocker status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tells you when the blocker gets switched off"
                }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("ScrollStop is switched off")
            .setContentText("Reels and Shorts aren't being blocked. Tap to turn it back on.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            nm.notify(ID_OFF, n)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
