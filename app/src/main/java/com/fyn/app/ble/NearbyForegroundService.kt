package com.fyn.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fyn.app.R
import com.fyn.app.core.AliasesStore
import com.fyn.app.core.ProfileCard

class NearbyForegroundService : Service() {

    private var advertiser: RidAdvertiser? = null
    private var gattServer: GattServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        // Start GATT server (serves your latest saved aliases)
        val store = AliasesStore(this)
        gattServer = GattServer(this) {
            ProfileCard(
                aliases = store.getAliases()
            )
        }.also { it.start() }

        // Start advertising
        advertiser = RidAdvertiser(this).also { it.start() }

        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // User tapped "Turn off" in the notification
                shutdownAndStop()
                return START_NOT_STICKY
            }
            else -> {
                // Ensure components are running if the service was restarted
                if (gattServer == null) {
                    val store = AliasesStore(this)
                    gattServer = GattServer(this) {
                        ProfileCard(aliases = store.getAliases())
                    }.also { it.start() }
                }
                if (advertiser == null) {
                    advertiser = RidAdvertiser(this).also { it.start() }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdownAndStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Notification with "Turn off" action ---
    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, NearbyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name) // your existing status icon
            .setContentTitle("Fyn sharing")
            .setContentText("Discoverable via Bluetooth")
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, // built-in icon (no new asset needed)
                "Turn off",
                stopPendingIntent
            )
            .build()
    }

    private fun shutdownAndStop() {
        runCatching { advertiser?.stop() }
        advertiser = null

        runCatching { gattServer?.stop() }
        gattServer = null

        // Remove the foreground notification and stop service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Fyn Nearby",
                NotificationManager.IMPORTANCE_LOW
            )
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "fyn.nearby"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.fyn.app.ACTION_STOP_DISCOVERABLE"
    }
}
