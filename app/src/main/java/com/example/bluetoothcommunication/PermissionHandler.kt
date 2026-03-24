package com.example.bluetoothcommunication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

// ─────────────────────────────────────────────────────────────────────────────
// PERMISSION HANDLER
// Launchers MUST be registered at class init time (before onStart)
// registerForActivityResult() inside a function call crashes with:
// "Attempting to register while current state is RESUMED"
// ─────────────────────────────────────────────────────────────────────────────
class PermissionHandler(private val activity: ComponentActivity) {

    val requiredPermissions: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    // ── Registered at init time — safe to call any time after ────────────────
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            permissionCallback?.invoke(results.values.all { it })
            permissionCallback = null
        }

    private val btEnableLauncher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { /* Bluetooth enable result — handled by system */ }

    // ── Check if all permissions granted ─────────────────────────────────────
    fun hasAllPermissions(): Boolean =
        requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

    // ── Request all permissions ───────────────────────────────────────────────
    fun requestPermissions(onResult: (Boolean) -> Unit) {
        permissionCallback = onResult
        permissionLauncher.launch(requiredPermissions)
    }

    // ── Request Bluetooth to be enabled ──────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun requestEnableBluetooth() {
        btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
}