package com.fyn.app.ble

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fyn.app.core.ProfileCard
import com.fyn.app.R
import com.fyn.app.core.AliasesStore

class NearbyForegroundService : Service() {

    private var advertiser: RidAdvertiser? = null
    private var gattServer: GattServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val store = AliasesStore(this)

        // 1) Start GATT SERVER HERE so it’s up whenever we advertise
        //    (Provide your real ProfileCard source if you have one)
        gattServer = GattServer(this) {
            // TODO: replace with your real card provider
            ProfileCard(
                aliases = store.getAliases()            )
        }.also { it.start() }

        // 2) Start advertising (RID)
        advertiser = RidAdvertiser(this).also { it.start() }

        // Foreground notification
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Fyn nearby sharing")
            .setContentText("Discoverable via Bluetooth")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure both are running in case the service is relaunched
        val store = AliasesStore(this)

        if (gattServer == null) {
            gattServer = GattServer(this) {
                ProfileCard(
                    aliases = store.getAliases()
                )
            }.also { it.start() }
        }
        if (advertiser == null) {
            advertiser = RidAdvertiser(this).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { advertiser?.stop() }
        advertiser = null
        runCatching { gattServer?.stop() }
        gattServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    }
}
