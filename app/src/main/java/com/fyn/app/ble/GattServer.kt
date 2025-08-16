package com.fyn.app.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fyn.app.core.Constants
import com.fyn.app.core.Crypto
import com.fyn.app.core.ProfileCard
import java.util.concurrent.ConcurrentHashMap

class GattServer(
    private val ctx: Context,
    private val profileCardProvider: () -> ProfileCard
) {

    private val btMgr = ctx.getSystemService(BluetoothManager::class.java)
    private var gattServer: BluetoothGattServer? = null

    // Rate limiting state
    private val servedTimestamps = ArrayDeque<Long>()
    private val peerLastTs = ConcurrentHashMap<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    // Per-connection ephemeral keys and session
    private val ephPriv = ConcurrentHashMap<String, java.security.PrivateKey>()
    private val sessionKey = ConcurrentHashMap<String, ByteArray>()

    fun start(): Boolean {
        return try {
            gattServer = btMgr.openGattServer(ctx, callback).apply {
                val service = BluetoothGattService(
                    Constants.GATT_SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
                )

                val caps = BluetoothGattCharacteristic(
                    Constants.CHAR_CAPS_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )

                // READ + WRITE (no NOTIFY dependency)
                val eph = BluetoothGattCharacteristic(
                    Constants.CHAR_EPH_PUB_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                // READ + WRITE (client will read the encrypted card after writing request)
                val env = BluetoothGattCharacteristic(
                    Constants.CHAR_ENVELOPE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                )

                val status = BluetoothGattCharacteristic(
                    Constants.CHAR_STATUS_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ, // keep simple; optional
                    BluetoothGattCharacteristic.PERMISSION_READ
                )

                addService(service.apply {
                    addCharacteristic(caps)
                    addCharacteristic(eph)
                    addCharacteristic(env)
                    addCharacteristic(status)
                })
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        gattServer?.close()
        gattServer = null
        ephPriv.clear()
        sessionKey.clear()
        servedTimestamps.clear()
        peerLastTs.clear()
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val key = device.address
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                ephPriv.remove(key)
                sessionKey.remove(key)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                Constants.CHAR_CAPS_UUID -> {
                    val caps = byteArrayOf(0x01) // protocol version 1
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, caps)
                }

                Constants.CHAR_EPH_PUB_UUID -> {
                    // If we don't have a public key prepared for this connection, create one now
                    if (characteristic.value == null || characteristic.value.isEmpty()) {
                        val kp = Crypto.generateEphemeralEcdh()
                        ephPriv[device.address] = kp.privateKey
                        characteristic.value = Crypto.publicKeyToBytes(kp.publicKey)
                    }
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, characteristic.value)
                }

                Constants.CHAR_ENVELOPE_UUID -> {
                    val value = characteristic.value ?: ByteArray(0)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                }

                else -> {
                    gattServer?.sendResponse(
                        device, requestId,
                        BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null
                    )
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val id = device.address
            when (characteristic.uuid) {
                Constants.CHAR_EPH_PUB_UUID -> {
                    // Client sent its ECDH public key
                    val peerPub = try { Crypto.bytesToPublicKey(value) } catch (_: Exception) {
                        if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        return
                    }

                    // Generate our ephemeral keypair, store private, expose public via READ
                    val ours = Crypto.generateEphemeralEcdh()
                    ephPriv[id] = ours.privateKey
                    characteristic.value = Crypto.publicKeyToBytes(ours.publicKey)

                    // Precompute session key
                    val shared = Crypto.ecdhSharedSecret(ours.privateKey, peerPub)
                    val sk = Crypto.hkdfSha256(
                        shared,
                        salt = "salt".toByteArray(),
                        info = "nearby-p2p".toByteArray(),
                        length = 32
                    )
                    sessionKey[id] = sk

                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }

                Constants.CHAR_ENVELOPE_UUID -> {
                    // Encrypted request arrived; enforce limits and prepare encrypted "card" into env.value
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }

                    val sk = sessionKey[id] ?: return
                    if (value.size < 13) return
                    val nonce = value.copyOfRange(0, 12)
                    val ct = value.copyOfRange(12, value.size)
                    val plain = try { Crypto.aesGcmDecrypt(sk, nonce, ct) } catch (_: Exception) { return }

                    if (!allowServe(device)) {
                        // Optionally set a status code characteristic, if you want.
                        return
                    }

                    // Prepare response envelope and store it so the client can READ it
                    val card = profileCardProvider()
                    val envelope = Crypto.packEnvelope(type = "card", rid = ByteArray(0), json = card.toJson())
                    val (n2, c2) = Crypto.aesGcmEncrypt(sk, envelope)
                    val resp = n2 + c2

                    val envChar = gattServer?.getService(Constants.GATT_SERVICE_UUID)
                        ?.getCharacteristic(Constants.CHAR_ENVELOPE_UUID)
                    envChar?.value = resp

                    // Optionally disconnect shortly after serving
                    handler.postDelayed({ gattServer?.cancelConnection(device) }, 200)
                }

                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    }
                }
            }
        }
    }

    private fun allowServe(device: BluetoothDevice): Boolean {
        val now = System.currentTimeMillis()
        // purge older than 15 min
        while (servedTimestamps.isNotEmpty() && now - servedTimestamps.first() > 15 * 60 * 1000) {
            servedTimestamps.removeFirst()
        }
        if (servedTimestamps.size >= Constants.GLOBAL_MAX_PER_15_MIN) return false

        val last = peerLastTs[device.address]
        if (last != null && now - last < Constants.PER_PEER_COOLDOWN_SECONDS * 1000L) return false

        servedTimestamps.addLast(now)
        peerLastTs[device.address] = now
        return true
    }
}
