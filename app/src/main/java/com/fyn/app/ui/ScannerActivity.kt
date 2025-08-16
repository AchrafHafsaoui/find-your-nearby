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
import android.os.*
import android.os.ParcelUuid
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fyn.app.R
import com.fyn.app.ble.GattClient
import com.fyn.app.ble.NearbyForegroundService
import com.fyn.app.core.Constants
import com.fyn.app.core.ProfileCard
import com.fyn.app.databinding.ActivityScannerBinding
import com.fyn.app.databinding.ItemPeerBinding

// ---- List models ----

data class Peer(
    val device: BluetoothDevice,
    val rssi: Int,
    val serviceData: ByteArray?,
    val aliases: Map<String, String>? = null // fetched socials (key -> username/handle)
)

class PeerDiff : DiffUtil.ItemCallback<Peer>() {
    override fun areItemsTheSame(oldItem: Peer, newItem: Peer) =
        oldItem.device.address == newItem.device.address
    override fun areContentsTheSame(oldItem: Peer, newItem: Peer) = oldItem == newItem
}

class PeerAdapter(private val onTap: (Peer) -> Unit) :
    ListAdapter<Peer, PeerVH>(PeerDiff()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PeerVH(ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false), onTap)
    override fun onBindViewHolder(holder: PeerVH, position: Int) =
        holder.bind(getItem(position))
}

class PeerVH(private val vb: ItemPeerBinding, private val onTap: (Peer) -> Unit) :
    RecyclerView.ViewHolder(vb.root) {

    fun bind(p: Peer) {
        vb.title.text = "Tap to view profile"
        vb.subtitle.text = "Signal: ${p.rssi}"

        // Render aliases (icon + username)
        val aliases = p.aliases.orEmpty().filterValues { !it.isNullOrBlank() }
        vb.aliasesContainer.removeAllViews()
        if (aliases.isNotEmpty()) {
            vb.aliasesContainer.visibility = View.VISIBLE
            val order = listOf("facebook", "instagram", "snapchat", "linkedin", "x", "tiktok")
            for (key in order) {
                val value = aliases[key] ?: continue
                addAliasRow(vb.aliasesContainer, iconFor(key), value)
            }
            // Render any extra, unknown keys at the end
            for ((k, v) in aliases) {
                if (k !in order) addAliasRow(vb.aliasesContainer, 0, "$k: $v")
            }
        } else {
            vb.aliasesContainer.visibility = View.GONE
        }

        vb.root.setOnClickListener { onTap(p) }
    }

    // Before
// private fun addAliasRow(container: LinearLayout, iconRes: Int, text: String)

    // After
    private fun addAliasRow(container: LinearLayout, iconRes: Int, label: String) {
        val ctx = container.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(), 0, 4.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        if (iconRes != 0) {
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(18.dp(), 18.dp()).also {
                    it.marginEnd = 8.dp()
                }
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            row.addView(iv)
        }

        val tv = TextView(ctx).apply {
            textSize = 14f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            text = label            // <-- use the renamed param
        }
        row.addView(tv)

        container.addView(row)
    }


    private fun iconFor(key: String): Int = when (key.lowercase()) {
        "facebook" -> R.drawable.ic_facebook
        "instagram" -> R.drawable.ic_instagram
        "snapchat" -> R.drawable.ic_snapchat
        "linkedin" -> R.drawable.ic_linkedin
        "x" -> R.drawable.ic_x
        "tiktok" -> R.drawable.ic_tiktok
        else -> 0
    }

    private fun Int.dp(): Int =
        (this * vb.root.resources.displayMetrics.density).toInt()
}

// ---- Activity ----

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding

    private val btMgr by lazy { getSystemService(BluetoothManager::class.java) }
    private val adapter: BluetoothAdapter by lazy { btMgr.adapter }
    private val scanner by lazy { adapter.bluetoothLeScanner }

    private val peers = mutableMapOf<String, Peer>()
    private val peerAdapter = PeerAdapter { onPeerTap(it) }

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
                peers[key] = Peer(
                    device = dev,
                    rssi = rssi,
                    serviceData = data,
                    aliases = prev?.aliases // keep any fetched aliases
                )
                renderPeers()
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

        binding.recycler.adapter = peerAdapter
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)

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
        renderPeers()

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

    private fun renderPeers() {
        val list = peers.values.sortedByDescending { it.rssi }
        peerAdapter.submitList(list.toList())
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

    private fun onPeerTap(peer: Peer) {
        val client = GattClient(this) { card: ProfileCard ->
            // keep only supported keys, retain order when rendering
            val supported = card.aliases
                .filterKeys { it.lowercase() in setOf("facebook","instagram","snapchat","linkedin","x","tiktok") }

            peers[peer.device.address]?.let { old ->
                peers[peer.device.address] = old.copy(aliases = supported)
                runOnUiThread { renderPeers() }
            }
        }
        stopScan() // reduce scan/connection interference
        client.connectAndFetch(peer.device)
    }

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
