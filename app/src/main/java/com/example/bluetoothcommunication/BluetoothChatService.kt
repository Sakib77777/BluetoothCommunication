package com.example.bluetoothcommunication

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class BluetoothChatService : Service() {

    companion object {
        private const val TAG          = "BluetoothChatService"
        const val ACTION_STOP          = "com.example.bluetoothcommunication.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NotificationHelper.NOTIFICATION_ID_SERVICE,
            NotificationHelper.buildServiceNotification(this)
        )
        Log.d(TAG, "Foreground service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-ensure BLE is alive (safe to call on already-running manager)
        val btManager = (application as BluetoothChatApp).btManager
        val prefs     = getSharedPreferences("BluetoothChat", MODE_PRIVATE)
        val fullName  = prefs.getString("username", "") ?: ""
        if (fullName.isNotBlank()) {
            btManager.startAdvertising(fullName)
            btManager.startContinuousScanning()
            Log.d(TAG, "BLE alive in background for: $fullName")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}