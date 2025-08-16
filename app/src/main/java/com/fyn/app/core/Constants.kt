package com.fyn.app.core

import java.util.*

object Constants {
    val SERVICE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb")
    val GATT_SERVICE_UUID: UUID = UUID.fromString("0000beef-0000-1000-8000-00805f9b34fb")

    // GATT characteristics
    val CHAR_CAPS_UUID: UUID = UUID.fromString("0000be01-0000-1000-8000-00805f9b34fb")
    val CHAR_EPH_PUB_UUID: UUID = UUID.fromString("0000be02-0000-1000-8000-00805f9b34fb")
    val CHAR_ENVELOPE_UUID: UUID = UUID.fromString("0000be03-0000-1000-8000-00805f9b34fb")
    val CHAR_STATUS_UUID: UUID = UUID.fromString("0000be04-0000-1000-8000-00805f9b34fb")

    // Rotation & limits
    const val ROTATE_SECONDS = 180      // 3 minutes
    const val GLOBAL_MAX_PER_15_MIN = 5
    const val PER_PEER_COOLDOWN_SECONDS = 120
    const val CONNECT_IDLE_TIMEOUT_MS = 5000L

    // RSSI threshold (tune)
    const val RSSI_THRESHOLD = -120
}
