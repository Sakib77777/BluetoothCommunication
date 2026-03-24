package com.example.bluetoothcommunication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    const val CHANNEL_MESSAGES          = "bt_chat_messages"
    const val CHANNEL_SERVICE           = "bt_chat_service"
    const val NOTIFICATION_ID_SERVICE   = 1001
    private const val NOTIFICATION_ID_MSG_BASE = 2000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description     = "Incoming Bluetooth Chat messages"
                enableVibration(true)
                enableLights(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Bluetooth Chat running in the background"
            }
        )
    }

    fun buildServiceNotification(context: Context): android.app.Notification {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Bluetooth Chat")
            .setContentText("Running in background — Bluetooth is active")
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showPrivateMessageNotification(
        context        : Context,
        senderName     : String,
        senderAvatar   : String,
        contactFullName: String,
        deviceId       : String,
        contactTag     : String
    ) {
        // TaskStackBuilder is the Android-recommended way to create a notification
        // PendingIntent that opens an activity with correct back stack.
        // It uses parentActivityName from manifest to build Home→Chat stack,
        // and correctly reuses existing ChatActivity instead of creating a duplicate.
        // Open HomeActivity on Nearby tab (tab index 1)
        // The contact card will show an unread dot — user taps to open chat
        val tap = PendingIntent.getActivity(
            context,
            contactFullName.hashCode(),
            Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("openTab",         1)              // open Nearby tab
                putExtra("unreadContact",   contactFullName) // highlight this contact
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Get current unread count to show in notification
        val unreadCount = UnreadStore.getCount(context, contactFullName)
        val countText   = if (unreadCount > 1) "$unreadCount unread messages" else "New private message"

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("$senderAvatar  $senderName")
            .setContentText(countText)
            .setNumber(unreadCount)   // shows count badge on app icon
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_MSG_BASE + deviceId.hashCode(), notification)
        } catch (_: SecurityException) {}
    }

    fun showBroadcastNotification(context: Context, senderName: String, messageText: String) {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("openTab", 0)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("📢  $senderName")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_MSG_BASE + senderName.hashCode(), notification)
        } catch (_: SecurityException) {}
    }

    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()
}