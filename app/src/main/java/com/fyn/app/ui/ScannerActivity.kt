package com.fyn.app.ui

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.os.ParcelUuid
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fyn.app.R
import com.fyn.app.ble.GattClient
import com.fyn.app.ble.NearbyForegroundService
import com.fyn.app.core.Constants
import com.fyn.app.core.ProfileCard
import com.fyn.app.databinding.ActivityScannerBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.ImageView
import android.util.TypedValue

data class Peer(
    val device: BluetoothDevice,
    val rssi: Int,
    val serviceData: ByteArray?,
    val angleRad: Float,                  // stable position on the radar
    val aliases: Map<String, String>? = null
)

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding

    private val btMgr by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter: BluetoothAdapter by lazy { btMgr.adapter }
    private val scanner by lazy { adapter.bluetoothLeScanner }

    private val peers = mutableMapOf<String, Peer>()
    private var isScanning = false

    private val runtimePerms = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUi() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            val rec = result.scanRecord ?: return
            val data = rec.getServiceData(ParcelUuid(Constants.SERVICE_UUID))
            val dev = result.device ?: return
            if (rssi < Constants.RSSI_THRESHOLD) return

            runOnUiThread {
                val key = dev.address
                val prev = peers[key]
                val angle = prev?.angleRad ?: angleForAddress(key)
                peers[key] = Peer(
                    device = dev,
                    rssi = rssi,
                    serviceData = data,
                    angleRad = angle,
                    aliases = prev?.aliases
                )
                renderRadar()
                binding.txtScanState.text = "Scanning: ON • ${peers.size} found"
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(this@ScannerActivity, "Scan failed: $errorCode", Toast.LENGTH_SHORT).show()
            stopScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.radar.onDotClick = dot@ { address ->
            val p = peers[address] ?: return@dot
            if (!p.aliases.isNullOrEmpty()) {
                showPeerSheet(p)
            } else {
                fetchAliasesThenShow(p)
            }
        }

        binding.btnStartScan.setOnClickListener { startScan() }
        binding.btnStopScan.setOnClickListener { stopScan() }

        binding.txtAdvertising.text = "Advertising: " +
                if (isServiceRunning(NearbyForegroundService::class.java)) "ON" else "OFF"

        updateUi()
    }

    private fun startScan() {
        if (!hasAllPerms()) {
            permLauncher.launch(runtimePerms)
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Turn on Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        peers.clear()
        renderRadar()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
        isScanning = true
        updateUi()

        binding.empty.postDelayed({
            if (isScanning && peers.isEmpty()) {
                binding.empty.text = "Scanning…\nNo nearby people yet"
            }
        }, 3000)
    }

    private fun stopScan() {
        runCatching { scanner.stopScan(scanCallback) }
        isScanning = false
        updateUi()
    }

    private fun renderRadar() {
        val list = peers.values.toList()
        binding.radar.setPeers(
            list.map {
                RadarPeer(
                    address = it.device.address,
                    rssi = it.rssi,
                    angleRad = it.angleRad,
                    hasAliases = !it.aliases.isNullOrEmpty()
                )
            }
        )
        binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateUi() {
        binding.txtScanState.text = if (isScanning) "Scanning: ON" else "Scanning: OFF"
        binding.progress.visibility = if (isScanning) View.VISIBLE else View.GONE
        binding.btnStartScan.isEnabled = !isScanning
        binding.btnStopScan.isEnabled = isScanning

        if (!hasAllPerms()) {
            binding.empty.visibility = View.VISIBLE
            binding.empty.text = "Permissions needed.\nTap START SCAN to grant."
        } else if (!isScanning && peers.isEmpty()) {
            binding.empty.visibility = View.VISIBLE
            binding.empty.text = "No nearby people yet.\nTap START SCAN."
        }
    }

    private fun hasAllPerms(): Boolean =
        runtimePerms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun fetchAliasesThenShow(peer: Peer) {
        val client = GattClient(this) { card: ProfileCard ->
            val supported = card.aliases.filterKeys {
                it.lowercase() in setOf("facebook","instagram","snapchat","linkedin","x","tiktok")
            }
            peers[peer.device.address]?.let { old ->
                val updated = old.copy(aliases = supported)
                peers[peer.device.address] = updated
                runOnUiThread {
                    renderRadar()
                    showPeerSheet(updated)
                }
            }
        }
        stopScan() // reduce scan/connection interference
        client.connectAndFetch(peer.device)
    }

    // —— Peer bottom sheet with clickable aliases ——
    private fun showPeerSheet(p: Peer) {
        val dialog = BottomSheetDialog(this)
        val ctx = this

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val title = TextView(ctx).apply {
            text = "Nearby person"
            textSize = 18f
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(title)

        val aliases = p.aliases.orEmpty()
        if (aliases.isEmpty()) {
            val tv = TextView(ctx).apply { text = "No aliases shared." }
            container.addView(tv)
        } else {
            val order = listOf("facebook", "instagram", "snapchat", "linkedin", "x", "tiktok")
            for (key in order) {
                aliases[key]?.let { addAliasRow(container, key, it) }
            }
            // extras
            for ((k,v) in aliases) if (k !in order) addAliasRow(container, k, "$k: $v", clickable = false)
        }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun addAliasRow(parent: LinearLayout, platform: String, handle: String, clickable: Boolean = true) {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val iv = ImageView(ctx).apply {
            setImageResource(iconFor(platform))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(10) }
        }
        val tv = TextView(ctx).apply {
            text = handle
            textSize = 16f
        }
        row.addView(iv)
        row.addView(tv)

        if (clickable) {
            val tvAttr = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvAttr, true)
            row.setBackgroundResource(tvAttr.resourceId)
            row.isClickable = true; row.isFocusable = true

            val url = buildProfileUrl(platform, handle)
            row.setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (_: Exception) { Toast.makeText(ctx, "Can't open link", Toast.LENGTH_SHORT).show() }
            }
        }

        parent.addView(row)
    }

    private fun iconFor(key: String): Int = when (key.lowercase()) {
        "facebook" -> R.drawable.ic_facebook
        "instagram" -> R.drawable.ic_instagram
        "snapchat" -> R.drawable.ic_snapchat
        "linkedin" -> R.drawable.ic_linkedin
        "x" -> R.drawable.ic_x
        "tiktok" -> R.drawable.ic_tiktok
        else -> android.R.drawable.ic_menu_info_details
    }

    private fun buildProfileUrl(platform: String, aliasRaw: String): String {
        val v = aliasRaw.trim()
        if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
        val h = v.removePrefix("@").trim()
        return when (platform.lowercase()) {
            "facebook" -> "https://facebook.com/$h"
            "instagram" -> "https://instagram.com/$h"
            "snapchat" -> "https://www.snapchat.com/add/$h"
            "linkedin" -> if (h.contains("/")) "https://www.linkedin.com/$h" else "https://www.linkedin.com/in/$h"
            "x" -> "https://x.com/$h"
            "tiktok" -> "https://www.tiktok.com/@${h.removePrefix("@")}"
            else -> "https://www.google.com/search?q=$h"
        }
    }

    private fun angleForAddress(address: String): Float {
        // stable pseudo-random angle per device
        val h = address.hashCode()
        val deg = ((h and 0x7fffffff) % 360)
        return (deg.toFloat() / 180f * Math.PI).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun <T> isServiceRunning(service: Class<T>): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == service.name }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scanner, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                AliasesSheet().show(supportFragmentManager, "aliases_sheet")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }
}
