package com.fyn.app.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.fyn.app.core.Constants
import com.fyn.app.core.RotatingId
import kotlin.random.Random

class RidAdvertiser(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val bt: BluetoothManager = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter = bt.adapter
    private var adv: BluetoothLeAdvertiser? = null

    private val seed = RotatingId.newSeed()
    private val rot = RotatingId(seed)

    private val tick = object : Runnable {
        override fun run() {
            restartAdvertising()
            handler.postDelayed(this, Constants.ROTATE_SECONDS * 1000L)
        }
    }

    fun start() {
        adv = adapter.bluetoothLeAdvertiser
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        adv?.stopAdvertising(callback)
    }

    private fun restartAdvertising() {
        adv?.stopAdvertising(callback)
        val now = System.currentTimeMillis() / 1000
        val rid = rot.currentRid(now, Constants.ROTATE_SECONDS)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .addServiceData(ParcelUuid(Constants.SERVICE_UUID), rid)
            .setIncludeDeviceName(false)
            .build()

        adv?.startAdvertising(settings, data, callback)
    }

    private val callback = object : android.bluetooth.le.AdvertiseCallback() {}
}
