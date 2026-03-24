package com.example.bluetoothcommunication

/**
 * Represents a discovered BLE peer shown in the Home screen contact list.
 *
 * This model bridges the raw BLE device data (from BluetoothChatManager)
 * and the UI layer (HomeScreen / ChatContactItem composables).
 *
 * Fields
 * ──────
 * @param id          BLE device MAC address — used as the unique key for
 *                    GATT connections and encryption key derivation.
 * @param username    Full username with device tag, e.g. "Sakib#A3F7".
 *                    Displayed in the contact row as "#A3F7".
 * @param displayName Human-readable name shown as the contact title,
 *                    e.g. "Sakib".
 * @param avatar      Emoji avatar chosen during UsernameSetupScreen.
 * @param lastMessage Preview text shown below the contact name.
 *                    Defaults to "Tap to connect & chat" for new contacts.
 * @param time        Timestamp label (e.g. "Now", "2 min ago", "12:30 PM").
 * @param isOnline    true = device is currently reachable via BLE.
 *                    Drives the green/grey status dot in the UI.
 * @param unreadCount Number of unread messages. Shows a blue badge when > 0.
 */
data class ChatContact(
    val id          : String,
    val username    : String,
    val displayName : String,
    val avatar      : String,
    val lastMessage : String    = "Tap to connect & chat",
    val time        : String    = "Now",
    val isOnline    : Boolean   = false,
    val unreadCount : Int       = 0,
    val macAddress  : String    = "",
    val reachType   : ReachType = ReachType.OFFLINE,
    val viaDevice   : String    = ""
)