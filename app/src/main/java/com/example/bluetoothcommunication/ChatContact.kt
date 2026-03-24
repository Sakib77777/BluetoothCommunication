package com.example.bluetoothcommunication

/**
 * Represents a discovered BLE peer shown in the Home screen contact list.
 *
 * @param id          Username "Name#TAG" — unique routing ID
 * @param username    Full username with device tag, e.g. "Sakib#A3F7"
 * @param displayName Human-readable name, e.g. "Sakib"
 * @param avatar      Emoji avatar received from the contact's PRESENCE broadcast
 * @param bio         Short bio/status received from the contact
 * @param lastMessage Preview text shown below the contact name
 * @param time        Timestamp label (e.g. "Now", "12:30 PM")
 * @param isOnline    true = device is currently reachable via BLE
 * @param unreadCount Number of unread messages — shows a badge when > 0
 * @param macAddress  Real BLE MAC address — used only for GATT connection
 * @param reachType   How this contact is reachable (DIRECT, MESH, OFFLINE)
 * @param viaDevice   Username of relay node when reachType == MESH
 */
data class ChatContact(
    val id          : String,
    val username    : String,
    val displayName : String,
    val avatar      : String,
    val bio         : String    = "",
    val lastMessage : String    = "Tap to connect & chat",
    val time        : String    = "Now",
    val isOnline    : Boolean   = false,
    val unreadCount : Int       = 0,
    val macAddress  : String    = "",
    val reachType   : ReachType = ReachType.OFFLINE,
    val viaDevice   : String    = ""
)