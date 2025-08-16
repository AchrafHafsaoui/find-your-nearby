package com.fyn.app.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.fyn.app.ble.GattServer
import com.fyn.app.ble.NearbyForegroundService
import com.fyn.app.core.ProfileCard

class MainActivity : ComponentActivity() {

    private val btMgr by lazy { getSystemService(BluetoothManager::class.java) }
    private val btAdapter by lazy { btMgr.adapter }

    private lateinit var gattServer: GattServer

    // ---- Runtimes we’ll ask for (vary by SDK) ----
    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            // Pre-Android 12 still ties scans to location permission
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        // Many OEMs still gate scanning behind location; keep both when present
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.distinct().toTypedArray()
    }

    // ---- Launchers ----
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = requiredPermissions().all { results[it] == true }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required for Bluetooth nearby discovery.", Toast.LENGTH_LONG).show()
            // Offer Settings if permanently denied
            maybeOpenAppSettings()
            return@registerForActivityResult
        }
        ensureBluetoothEnabled()
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // User may or may not have enabled BT; check and continue
        if (btAdapter?.isEnabled == true) startAppServices()
        else Toast.makeText(this, "Please enable Bluetooth to continue.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI here; we’ll immediately go to ScannerActivity after setup
        requestAllNeeded()
    }

    private fun requestAllNeeded() {
        val missing = requiredPermissions().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permLauncher.launch(requiredPermissions())
        } else {
            ensureBluetoothEnabled()
        }
    }

    private fun ensureBluetoothEnabled() {
        val adapter = btAdapter
        if (adapter == null) {
            Toast.makeText(this, "This device doesn’t support Bluetooth LE.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            startAppServices()
        }
    }

    private fun startAppServices() {
        // Start always-on advertising foreground service
        startService(Intent(this, NearbyForegroundService::class.java))


        // Go to scanner UI
        startActivity(Intent(this, ScannerActivity::class.java))
        finish()
    }

    private fun maybeOpenAppSettings() {
        // If any permission is permanently denied, suggest Settings
        val showSettings = requiredPermissions().any { perm ->
            // If not granted and shouldShowRequestPermissionRationale == false,
            // it's likely "Don't ask again"
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(perm)
        }
        if (showSettings) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
    }
}
