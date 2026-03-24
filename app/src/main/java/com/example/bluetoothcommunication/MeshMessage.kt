package com.example.bluetoothcommunication

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MeshMessage(
    val id               : String = UUID.randomUUID().toString(),
    val senderId         : String,
    val senderName       : String,
    val recipientId      : String,
    val encryptedContent : String,
    val hop              : Int    = 0,
    val maxHops          : Int    = MAX_HOPS,
    val timestamp        : Long   = System.currentTimeMillis(),
    val type             : String = TYPE_CHAT,
    val knownPeers       : List<String> = emptyList(),
    val avatar           : String = "",   // sender's emoji avatar — populated in PRESENCE
    val bio              : String = ""    // sender's short bio/status — populated in PRESENCE
) {
    fun toJson(): String = JSONObject().apply {
        put("id",    id)
        put("from",  senderId)
        put("fn",    senderName)
        put("to",    recipientId)
        put("data",  encryptedContent)
        put("hop",   hop)
        put("max",   maxHops)
        put("ts",    timestamp)
        put("type",  type)
        if (knownPeers.isNotEmpty()) {
            put("peers", JSONArray(knownPeers))
        }
        // Include avatar + bio only in PRESENCE messages
        if (type == TYPE_PRESENCE) {
            if (avatar.isNotEmpty()) put("avatar", avatar)
            if (bio.isNotEmpty())    put("bio",    bio)
        }
    }.toString()

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        const val BROADCAST      = "BROADCAST"
        const val MAX_HOPS       = 5
        const val TYPE_CHAT      = "CHAT"
        const val TYPE_BROADCAST = "BROADCAST_MSG"
        const val TYPE_PRESENCE  = "PRESENCE"

        fun fromJson(json: String): MeshMessage? = runCatching {
            JSONObject(json).let { o ->
                val peersArr = o.optJSONArray("peers")
                val peers    = if (peersArr != null)
                    (0 until peersArr.length()).map { peersArr.getString(it) }
                else emptyList()

                MeshMessage(
                    id               = o.getString("id"),
                    senderId         = o.getString("from"),
                    senderName       = o.getString("fn"),
                    recipientId      = o.getString("to"),
                    encryptedContent = o.optString("data", ""),
                    hop              = o.getInt("hop"),
                    maxHops          = o.getInt("max"),
                    timestamp        = o.getLong("ts"),
                    type             = o.optString("type", TYPE_CHAT),
                    knownPeers       = peers,
                    avatar           = o.optString("avatar", ""),
                    bio              = o.optString("bio",    "")
                )
            }
        }.getOrNull()

        fun fromBytes(bytes: ByteArray): MeshMessage? =
            fromJson(String(bytes, Charsets.UTF_8))
    }
}