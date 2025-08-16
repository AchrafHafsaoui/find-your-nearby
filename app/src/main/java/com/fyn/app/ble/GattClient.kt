package com.fyn.app.ble

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fyn.app.core.Constants
import com.fyn.app.core.Crypto
import com.fyn.app.core.ProfileCard

class GattClient(
    private val ctx: Context,
    private val onCard: (ProfileCard) -> Unit
) {

    private var gatt: BluetoothGatt? = null
    private var sessionKey: ByteArray? = null
    private val eph = Crypto.generateEphemeralEcdh()
    private val handler = Handler(Looper.getMainLooper())

    private var wrotePub = false
    private var gotPeerPub = false
    private var sentReq = false

    private val idleTimeout = Constants.CONNECT_IDLE_TIMEOUT_MS
    private val timeoutRunnable = Runnable { safeDisconnect() }

    fun connectAndFetch(device: BluetoothDevice) {
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(timeoutRunnable, idleTimeout)
    }

    private fun safeDisconnect() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Request a larger MTU before any writes (safer across stacks)
                g.requestMtu(185) // 185 is widely supported; 512 also fine if you prefer
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(timeoutRunnable)
                g.close()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Proceed after MTU negotiation
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Constants.GATT_SERVICE_UUID) ?: return g.disconnect()
            val ephChar = svc.getCharacteristic(Constants.CHAR_EPH_PUB_UUID) ?: return g.disconnect()

            wrotePub = false
            gotPeerPub = false
            sentReq = false

            // 1) Write our ECDH pubkey
            ephChar.value = Crypto.publicKeyToBytes(eph.publicKey)
            g.writeCharacteristic(ephChar)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (c.uuid) {
                Constants.CHAR_EPH_PUB_UUID -> {
                    if (!wrotePub) {
                        wrotePub = true
                        // 2) Read server's pubkey (server sets it in same char's value)
                        g.readCharacteristic(c)
                    }
                }
                Constants.CHAR_ENVELOPE_UUID -> {
                    if (!sentReq) {
                        sentReq = true
                        // 4) Read back the encrypted card
                        g.readCharacteristic(c)
                    }
                }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (c.uuid) {
                Constants.CHAR_EPH_PUB_UUID -> {
                    if (!gotPeerPub) {
                        gotPeerPub = true
                        // 3) Derive session key and send encrypted "request"
                        val peerPub = try { Crypto.bytesToPublicKey(c.value) } catch (_: Exception) {
                            safeDisconnect(); return
                        }
                        val shared = Crypto.ecdhSharedSecret(eph.privateKey, peerPub)
                        sessionKey = Crypto.hkdfSha256(shared, "salt".toByteArray(), "nearby-p2p".toByteArray(), 32)

                        val env = g.getService(Constants.GATT_SERVICE_UUID)
                            ?.getCharacteristic(Constants.CHAR_ENVELOPE_UUID) ?: return

                        val envelope = Crypto.packEnvelope(type = "request", rid = ByteArray(0), json = "{}")
                        val (nonce, ct) = Crypto.aesGcmEncrypt(sessionKey!!, envelope)
                        env.value = nonce + ct
                        g.writeCharacteristic(env)
                    }
                }

                Constants.CHAR_ENVELOPE_UUID -> {
                    val sk = sessionKey ?: return
                    val value = c.value
                    if (value.size < 13) return
                    val nonce = value.copyOfRange(0, 12)
                    val ct = value.copyOfRange(12, value.size)
                    val plain = try { Crypto.aesGcmDecrypt(sk, nonce, ct) } catch (_: Exception) { return }
                    val (type, _, json) = Crypto.unpackEnvelope(plain)
                    if (type == "card") {
                        handler.removeCallbacks(timeoutRunnable)
                        val card = ProfileCard.fromJson(json)
                        onCard(card)
                        g.disconnect()
                    }
                }
            }
        }
    }
}
