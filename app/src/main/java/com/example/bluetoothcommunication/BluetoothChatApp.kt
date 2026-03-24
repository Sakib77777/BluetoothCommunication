package com.example.bluetoothcommunication

import android.app.Application
import android.content.Intent
import android.os.Build

class BluetoothChatApp : Application() {

    lateinit var btManager: BluetoothChatManager
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        btManager = BluetoothChatManager(this)
        // Start foreground service — keeps BLE running when app is in background
        val svc = Intent(this, BluetoothChatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }
}