package com.coralreef

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    private const val CHANNEL_CLAIMS   = "catering_claims"
    private const val CHANNEL_ALERTS   = "catering_alerts"
    private const val CHANNEL_SKIPPED  = "catering_skipped"

    private var notifId = 1000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Claims: DEFAULT importance — shows in status bar + sound, no popup banner
        // (heads-up banners cover the Dasher calendar and confuse the accessibility service)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CLAIMS, "Claimed Offers",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Fires when the bot successfully claims a catering offer"
        })

        // Alerts (bot stopped/paused): HIGH importance — heads-up banner is appropriate here
        // since the user needs to know and the bot is no longer running
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ALERTS, "Bot Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Fires when the bot stops or pauses unexpectedly"
            enableVibration(true)
        })

        // Skipped: silent, status bar only
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_SKIPPED, "Skipped Offers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent log of offers that didn't meet filters"
        })
    }

    /** ✅ Show when an offer is successfully claimed */
    fun notifyClaimed(ctx: Context, details: DasherAccessibilityService.OfferDetails) {
        show(ctx, CHANNEL_CLAIMS,
            title = "✅ Offer Claimed!",
            body  = "\$${details.payout} | ${details.pickupTime} | ${details.restaurant}",
            id    = notifId++
        )
    }

    /** ⚠️ Show when the bot stops due to a dangerous screen (upcoming order, active dash) */
    fun notifyBotStopped(ctx: Context, reason: String) {
        show(ctx, CHANNEL_ALERTS,
            title = "⚠️ Bot Stopped",
            body  = reason,
            id    = 1  // fixed ID — updates in place rather than stacking
        )
    }

    /** ⏸ Show when the bot auto-pauses because it lost Dasher or navigation failed */
    fun notifyBotPaused(ctx: Context, reason: String) {
        show(ctx, CHANNEL_ALERTS,
            title = "⏸ Bot Paused",
            body  = "$reason — Tap to open app",
            id    = 2
        )
    }

    /** ⏭ Show when an offer is skipped due to filters */
    fun notifySkipped(ctx: Context, reason: String, payout: Double) {
        show(ctx, CHANNEL_SKIPPED,
            title = "⏭ Offer Skipped (\$${"%.2f".format(payout)})",
            body  = reason,
            id    = notifId++
        )
    }

    /** ℹ️ General info (conflict, unavailable, etc.) */
    fun notifyInfo(ctx: Context, title: String, body: String) {
        show(ctx, CHANNEL_ALERTS,
            title = title,
            body  = body,
            id    = notifId++
        )
    }

    private fun show(ctx: Context, channel: String, title: String, body: String, id: Int) {
        try {
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(
                    when (channel) {
                        CHANNEL_ALERTS  -> NotificationCompat.PRIORITY_HIGH    // bot stopped — heads-up ok
                        CHANNEL_CLAIMS  -> NotificationCompat.PRIORITY_DEFAULT  // claimed — status bar only
                        else            -> NotificationCompat.PRIORITY_LOW       // skipped — silent
                    }
                )
                .build()

            NotificationManagerCompat.from(ctx).notify(id, notif)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted yet — silent fail
        }
    }
}
