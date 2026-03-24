package com.example.bluetoothcommunication

enum class ReachType { DIRECT, MESH, OFFLINE }

/**
 * Represents a BLE device discovered during scanning.
 *
 * @param id             Username "Name#TAG" — stable unique routing ID
 * @param macAddress     Real BLE MAC — only for connectGatt()
 * @param username       Full "Name#TAG"
 * @param displayName    "Name" part (before #)
 * @param avatar         Emoji avatar (received via PRESENCE message)
 * @param bio            Short bio/status text (received via PRESENCE message)
 * @param signalStrength RSSI in dBm
 * @param lastSeen       Timestamp of last scan result
 * @param reachType      DIRECT = in BLE range, MESH = reachable via relay, OFFLINE = not reachable
 * @param viaDevice      Username of relay device (only set when reachType == MESH)
 */
data class DiscoveredDevice(
    val id             : String,
    val macAddress     : String    = "",
    val username       : String,
    val displayName    : String,
    val avatar         : String    = "🧑",
    val bio            : String    = "",
    val signalStrength : Int       = -100,
    val lastSeen       : Long      = System.currentTimeMillis(),
    val reachType      : ReachType = ReachType.DIRECT,
    val viaDevice      : String    = ""
)