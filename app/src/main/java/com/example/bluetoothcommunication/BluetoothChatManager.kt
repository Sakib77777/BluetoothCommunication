package com.example.bluetoothcommunication

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

object BLEConstants {
    val SERVICE_UUID                : UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    val MESSAGE_CHARACTERISTIC_UUID : UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    val DESCRIPTOR_UUID             : UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    const val TAG = "BluetoothChat"
}

sealed class BLEState {
    object Idle         : BLEState()
    object Scanning     : BLEState()
    object Advertising  : BLEState()
    object Connecting   : BLEState()
    object Connected    : BLEState()
    object Disconnected : BLEState()
    data class Error(val message: String) : BLEState()
}

@SuppressLint("MissingPermission")
class BluetoothChatManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter : BluetoothAdapter?      = bluetoothManager.adapter
    private var bleScanner       : BluetoothLeScanner?    = null
    private var bleAdvertiser    : BluetoothLeAdvertiser? = null
    private var gattServer       : BluetoothGattServer?   = null

    private val clientGatts      = mutableMapOf<String, BluetoothGatt>()
    private val connectedClients = mutableListOf<BluetoothDevice>()
    private val seenMessageIds   = mutableSetOf<String>()

    private var myAddress      : String = ""
    private var myFullUsername : String = ""

    // Tracks the ID of the most recently sent message so we can ack it
    // when onCharacteristicWrite fires with GATT_SUCCESS
    private var pendingAckMessageId: String = ""

    private val scanHandler    = Handler(Looper.getMainLooper())
    private var isScanning     = false
    private var scanRestartJob : Runnable? = null

    // ── Flows observed by UI ──────────────────────────────────────────────────
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    private val _receivedMessages  = MutableStateFlow<List<ChatMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ChatMessage>> = _receivedMessages

    private val _broadcastMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val broadcastMessages: StateFlow<List<ChatMessage>> = _broadcastMessages

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    // ── NEW: Delivery ack flow ────────────────────────────────────────────────
    // Emits the message ID when BLE confirms the write succeeded (real SENT)
    private val _deliveredAcks = MutableStateFlow("")
    val deliveredAcks: StateFlow<String> = _deliveredAcks

    // ── NEW: Read receipt flow ────────────────────────────────────────────────
    // Emits the original message ID when the recipient sends a read receipt
    private val _readReceipts = MutableStateFlow("")
    val readReceipts: StateFlow<String> = _readReceipts

    val onlineCount: Int get() = _discoveredDevices.value.size

    // ─────────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ─────────────────────────────────────────────────────────────────────────
    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)      == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)   == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // ─────────────────────────────────────────────────────────────────────────
    // SETUP GATT SERVER
    // ─────────────────────────────────────────────────────────────────────────
    fun setupGattServer() {
        if (!hasPermissions()) return
        if (gattServer != null) return

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val characteristic = BluetoothGattCharacteristic(
            BLEConstants.MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ  or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_WRITE
        ).apply {
            addDescriptor(BluetoothGattDescriptor(
                BLEConstants.DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            ))
        }

        val service = BluetoothGattService(
            BLEConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply { addCharacteristic(characteristic) }

        gattServer?.addService(service)
        myAddress = bluetoothAdapter?.address ?: ""
        Log.d(BLEConstants.TAG, "✅ GATT Server ready")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADVERTISING
    // ─────────────────────────────────────────────────────────────────────────
    fun startAdvertising(myUsername: String) {
        if (!hasPermissions() || !isBluetoothEnabled()) return

        bleAdvertiser          = bluetoothAdapter?.bluetoothLeAdvertiser
        bluetoothAdapter?.name = myUsername.take(20)
        myAddress              = bluetoothAdapter?.address ?: ""
        myFullUsername         = myUsername

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID))
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        Log.d(BLEConstants.TAG, "📡 Advertising as: $myUsername")
    }

    fun stopAdvertising() {
        if (!hasPermissions()) return
        bleAdvertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(BLEConstants.TAG, "✅ Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(BLEConstants.TAG, "❌ Advertising failed: $errorCode")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTINUOUS SCANNING
    // ─────────────────────────────────────────────────────────────────────────
    fun startContinuousScanning() {
        if (!hasPermissions() || !isBluetoothEnabled()) return
        isScanning = true
        doStartScan()
        scheduleRestart()
        Log.d(BLEConstants.TAG, "🔍 Continuous scanning started")
    }

    private fun doStartScan() {
        if (!hasPermissions()) return
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}

        val filter   = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun scheduleRestart() {
        scanRestartJob?.let { scanHandler.removeCallbacks(it) }
        scanRestartJob = Runnable {
            if (!isScanning) return@Runnable
            if (hasPermissions()) bleScanner?.stopScan(scanCallback)
            scanHandler.postDelayed({
                if (isScanning) {
                    doStartScan()
                    pruneStaleDevices()
                    scheduleRestart()
                }
            }, 500)
        }
        scanHandler.postDelayed(scanRestartJob!!, 8_000)
    }

    private fun pruneStaleDevices() {
        val cutoff     = System.currentTimeMillis() - 45_000
        val directOk   = _discoveredDevices.value.filter { it.reachType == ReachType.DIRECT && it.lastSeen > cutoff }
        val directNames = directOk.map { it.username }.toSet()
        val meshOk     = _discoveredDevices.value.filter { it.reachType == ReachType.MESH && it.viaDevice in directNames }
        val updated    = directOk + meshOk
        if (updated.size != _discoveredDevices.value.size) _discoveredDevices.value = updated
    }

    fun stopScanning() {
        isScanning = false
        scanRestartJob?.let { scanHandler.removeCallbacks(it) }
        scanRestartJob = null
        if (!hasPermissions()) return
        bleScanner?.stopScan(scanCallback)
        Log.d(BLEConstants.TAG, "🛑 Scanning stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device     = result.device
            val rssi       = result.rssi
            val deviceName = result.scanRecord?.deviceName ?: device.name ?: ""
            if (deviceName.isBlank() || !deviceName.contains("#")) return

            val uniqueId   = deviceName
            val macAddress = device.address
            val list       = _discoveredDevices.value.toMutableList()
            val index      = list.indexOfFirst { it.username == uniqueId }
            val found = DiscoveredDevice(
                id             = uniqueId,
                macAddress     = macAddress,
                username       = deviceName,
                displayName    = deviceName.substringBefore("#"),
                avatar         = list.getOrNull(index)?.avatar ?: "🧑",
                bio            = list.getOrNull(index)?.bio    ?: "",
                signalStrength = rssi,
                lastSeen       = System.currentTimeMillis()
            )
            val isNew = index < 0
            if (index >= 0) list[index] = found else list.add(found)
            _discoveredDevices.value = list

            if (isNew && !clientGatts.containsKey(macAddress)) {
                Log.d(BLEConstants.TAG, "Auto-connecting to: $deviceName ($macAddress)")
                connectToDevice(macAddress)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(BLEConstants.TAG, "❌ Scan failed: $errorCode")
            if (isScanning) scanHandler.postDelayed({ if (isScanning) doStartScan() }, 3_000)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONNECT / DISCONNECT
    // ─────────────────────────────────────────────────────────────────────────
    fun connectToDevice(deviceId: String) {
        if (!hasPermissions()) return
        if (clientGatts.containsKey(deviceId)) return
        val device = bluetoothAdapter?.getRemoteDevice(deviceId) ?: return
        _connectionState.value = "Connecting..."
        val gatt = device.connectGatt(context, false, gattClientCallback)
        clientGatts[deviceId] = gatt
        Log.d(BLEConstants.TAG, "🔗 Connecting to $deviceId")
    }

    fun disconnect(deviceId: String? = null) {
        if (!hasPermissions()) return
        if (deviceId != null) {
            clientGatts[deviceId]?.disconnect()
            clientGatts[deviceId]?.close()
            clientGatts.remove(deviceId)
        } else {
            clientGatts.values.forEach { it.disconnect(); it.close() }
            clientGatts.clear()
        }
        if (clientGatts.isEmpty() && connectedClients.isEmpty())
            _connectionState.value = "Disconnected"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEND MESSAGE
    // messageId: caller supplies it so ChatScreen can track delivery by the same ID
    // ─────────────────────────────────────────────────────────────────────────
    fun sendMessage(
        encryptedText : String,
        myUsername    : String,
        recipientId   : String = MeshMessage.BROADCAST,
        messageId     : String = UUID.randomUUID().toString()
    ) {
        if (!hasPermissions()) return

        val msg = MeshMessage(
            id               = messageId,
            senderId         = myAddress,
            senderName       = myUsername,
            recipientId      = recipientId,
            encryptedContent = encryptedText
        )

        // Remember this ID — onCharacteristicWrite will ack it on success
        pendingAckMessageId = messageId

        seenMessageIds.add(msg.id)
        transmitRaw(msg.toBytes(), skipAddress = null)
        Log.d(BLEConstants.TAG, "📤 Sent msg $messageId to $recipientId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEND READ RECEIPT
    // Called by ChatScreen when the user opens a chat and sees a message.
    // encryptedContent carries the original message ID being acknowledged.
    // ─────────────────────────────────────────────────────────────────────────
    fun sendReadReceipt(originalMessageId: String, toUsername: String) {
        if (!hasPermissions()) return
        val receipt = MeshMessage(
            senderId         = myFullUsername,
            senderName       = myFullUsername,
            recipientId      = toUsername,
            encryptedContent = originalMessageId,   // original msg ID being ack'd
            type             = MeshMessage.TYPE_READ_RECEIPT
        )
        seenMessageIds.add(receipt.id)
        transmitRaw(receipt.toBytes(), skipAddress = null)
        Log.d(BLEConstants.TAG, "📬 Sent read receipt for $originalMessageId to $toUsername")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROUTE / RELAY
    // ─────────────────────────────────────────────────────────────────────────
    private fun routeMessage(msg: MeshMessage, receivedFromAddress: String) {
        if (msg.hop >= msg.maxHops) return
        transmitRaw(msg.copy(hop = msg.hop + 1).toBytes(), skipAddress = receivedFromAddress)
    }

    private fun transmitRaw(bytes: ByteArray, skipAddress: String?) {
        clientGatts.forEach { (address, gatt) ->
            if (address == skipAddress) return@forEach
            val char = gatt.getService(BLEConstants.SERVICE_UUID)
                ?.getCharacteristic(BLEConstants.MESSAGE_CHARACTERISTIC_UUID) ?: return@forEach
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        val serverChar = gattServer
            ?.getService(BLEConstants.SERVICE_UUID)
            ?.getCharacteristic(BLEConstants.MESSAGE_CHARACTERISTIC_UUID) ?: return

        serverChar.value = bytes
        connectedClients.forEach { device ->
            if (device.address == skipAddress) return@forEach
            gattServer?.notifyCharacteristicChanged(device, serverChar, false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRESENCE — sends avatar + bio + known peers to new connection
    // ─────────────────────────────────────────────────────────────────────────
    private fun sendPresenceTo(gatt: android.bluetooth.BluetoothGatt) {
        if (!hasPermissions()) return
        val directPeers = _discoveredDevices.value
            .filter { it.reachType == ReachType.DIRECT }
            .map { it.username }

        val prefs    = context.getSharedPreferences("BluetoothChat", Context.MODE_PRIVATE)
        val myAvatar = prefs.getString("avatar", "🧑") ?: "🧑"
        val myBio    = prefs.getString("bio",    "")   ?: ""

        val presence = MeshMessage(
            senderId         = myFullUsername,
            senderName       = myFullUsername,
            recipientId      = MeshMessage.BROADCAST,
            encryptedContent = "",
            type             = MeshMessage.TYPE_PRESENCE,
            knownPeers       = directPeers,
            avatar           = myAvatar,
            bio              = myBio
        )

        val bytes   = presence.toBytes()
        val service = gatt.getService(BLEConstants.SERVICE_UUID) ?: return
        val char    = service.getCharacteristic(BLEConstants.MESSAGE_CHARACTERISTIC_UUID) ?: return
        try {
            @Suppress("DEPRECATION")
            char.value     = bytes
            char.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        } catch (e: Exception) {
            Log.e(BLEConstants.TAG, "Presence send failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROCESS INCOMING MESSAGE
    // ─────────────────────────────────────────────────────────────────────────
    private fun processIncomingBytes(bytes: ByteArray, fromAddress: String) {
        val msg = MeshMessage.fromBytes(bytes) ?: run {
            Log.w(BLEConstants.TAG, "⚠️ Could not parse message")
            return
        }

        if (msg.id in seenMessageIds) {
            Log.d(BLEConstants.TAG, "🔄 Duplicate dropped")
            return
        }
        seenMessageIds.add(msg.id)

        when {
            // ── PRESENCE ─────────────────────────────────────────────────────
            msg.type == MeshMessage.TYPE_PRESENCE -> {
                val updatedList = _discoveredDevices.value.toMutableList()
                val idx = updatedList.indexOfFirst { it.username == msg.senderName }
                if (idx >= 0) {
                    val existing = updatedList[idx]
                    updatedList[idx] = existing.copy(
                        avatar = msg.avatar.ifEmpty { existing.avatar },
                        bio    = msg.bio.ifEmpty    { existing.bio    }
                    )
                    _discoveredDevices.value = updatedList
                }
                msg.knownPeers.forEach { peer ->
                    if (peer != myFullUsername && _discoveredDevices.value.none { it.username == peer }) {
                        val list = _discoveredDevices.value.toMutableList()
                        list.add(DiscoveredDevice(
                            id = peer, username = peer,
                            displayName = peer.substringBefore("#"),
                            reachType = ReachType.MESH, viaDevice = msg.senderName,
                            lastSeen = System.currentTimeMillis()
                        ))
                        _discoveredDevices.value = list
                    }
                }
                return
            }

            // ── READ RECEIPT — update the original sender's message to READ ──
            msg.type == MeshMessage.TYPE_READ_RECEIPT && msg.recipientId == myFullUsername -> {
                // msg.encryptedContent = original message ID being acknowledged
                Log.d(BLEConstants.TAG, "📬 Read receipt for msg: ${msg.encryptedContent}")
                _readReceipts.value = msg.encryptedContent
                return
            }

            // ── BROADCAST ────────────────────────────────────────────────────
            msg.recipientId == MeshMessage.BROADCAST -> {
                appendBroadcastToUi(msg.encryptedContent, msg.id, msg.senderName)
                NotificationHelper.showBroadcastNotification(
                    context = context, senderName = msg.senderName, messageText = msg.encryptedContent
                )
                routeMessage(msg, receivedFromAddress = fromAddress)
                return
            }

            // ── PRIVATE — for me ──────────────────────────────────────────────
            msg.recipientId == myFullUsername -> {
                Log.d(BLEConstants.TAG, "🔐 Private msg from ${msg.senderName}")
                appendToUi(msg.encryptedContent, msg.id, msg.senderName)
                UnreadStore.markUnread(context, msg.senderName)
                NotificationHelper.showPrivateMessageNotification(
                    context         = context,
                    senderName      = msg.senderName.substringBefore("#"),
                    senderAvatar    = "🧑",
                    contactFullName = msg.senderName,
                    deviceId        = fromAddress,
                    contactTag      = msg.senderName.substringAfter("#", "")
                )
                return
            }

            // ── RELAY ─────────────────────────────────────────────────────────
            else -> {
                routeMessage(msg, receivedFromAddress = fromAddress)
                return
            }
        }
    }

    private fun appendToUi(encryptedContent: String, messageId: String? = null, senderName: String = "") {
        _receivedMessages.value += ChatMessage(
            id         = messageId ?: UUID.randomUUID().toString(),
            text       = encryptedContent,
            isMine     = false,
            senderName = senderName,
            status     = MessageStatus.DELIVERED
        )
    }

    private fun appendBroadcastToUi(text: String, messageId: String? = null, senderName: String = "") {
        _broadcastMessages.value += ChatMessage(
            id         = messageId ?: UUID.randomUUID().toString(),
            text       = text,
            isMine     = false,
            senderName = senderName,
            status     = MessageStatus.DELIVERED
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GATT CLIENT CALLBACK
    // ─────────────────────────────────────────────────────────────────────────
    private val gattClientCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(BLEConstants.TAG, "✅ Client connected to ${gatt.device.address}")
                    _connectionState.value = "Connected"
                    if (hasPermissions()) gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(BLEConstants.TAG, "🔌 Client disconnected from ${gatt.device.address}")
                    clientGatts.remove(gatt.device.address)
                    if (clientGatts.isEmpty() && connectedClients.isEmpty())
                        _connectionState.value = "Disconnected"
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (hasPermissions()) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !hasPermissions()) return
            val char = gatt.getService(BLEConstants.SERVICE_UUID)
                ?.getCharacteristic(BLEConstants.MESSAGE_CHARACTERISTIC_UUID) ?: return
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(BLEConstants.DESCRIPTOR_UUID) ?: return
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
            sendPresenceTo(gatt)
        }

        // ── onCharacteristicWrite: BLE confirmed the write succeeded ──────────
        // This is the REAL delivery confirmation — emit the pending message ID
        // so ChatScreen can update its status from SENDING → SENT
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && pendingAckMessageId.isNotEmpty()) {
                Log.d(BLEConstants.TAG, "✅ BLE write confirmed for msg: $pendingAckMessageId")
                _deliveredAcks.value = pendingAckMessageId
                pendingAckMessageId  = ""
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(BLEConstants.TAG, "❌ Write failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            processIncomingBytes(value, gatt.device.address)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            processIncomingBytes(characteristic.value ?: return, gatt.device.address)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GATT SERVER CALLBACK
    // ─────────────────────────────────────────────────────────────────────────
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!connectedClients.contains(device)) connectedClients.add(device)
                    _connectionState.value = "Connected"
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedClients.remove(device)
                    if (clientGatts.isEmpty() && connectedClients.isEmpty())
                        _connectionState.value = "Disconnected"
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            processIncomingBytes(value, device.address)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────────────────
    fun cleanup() {
        stopScanning()
        stopAdvertising()
        disconnect()
        gattServer?.close()
        gattServer = null
        connectedClients.clear()
        seenMessageIds.clear()
        _broadcastMessages.value = emptyList()
        Log.d(BLEConstants.TAG, "🧹 Cleaned up")
    }
}