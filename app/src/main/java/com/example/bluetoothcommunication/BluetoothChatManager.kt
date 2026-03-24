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

// ─────────────────────────────────────────────────────────────────────────────
// MESH ROUTING OVERVIEW
//
//   Phone A  ──BLE──►  Phone B  ──BLE──►  Phone C
//
//   A sends MeshMessage { senderId="A", recipientId="C", hop=0 }
//   B receives → recipientId ≠ B → relay to C (hop becomes 1)
//   C receives → recipientId == C → decrypt + deliver to UI
//
// Loop prevention : seenMessageIds set
// Multi-connection: clientGatts map (one BluetoothGatt per peer)
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("MissingPermission")
class BluetoothChatManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter : BluetoothAdapter?      = bluetoothManager.adapter
    private var bleScanner       : BluetoothLeScanner?    = null
    private var bleAdvertiser    : BluetoothLeAdvertiser? = null
    private var gattServer       : BluetoothGattServer?   = null

    // Multi-connection maps
    private val clientGatts      = mutableMapOf<String, BluetoothGatt>()
    private val connectedClients = mutableListOf<BluetoothDevice>()

    // Mesh deduplication
    private val seenMessageIds = mutableSetOf<String>()

    // My own address
    private var myAddress     : String = ""
    private var myFullUsername: String = ""  // "Name#TAG" — used to identify messages for us

    // Continuous scan handler — restarts scan every 25s to bypass Android's
    // 30-second scan timeout without triggering the throttle (Android kills
    // scans that run exactly 30s repeatedly too quickly)
    private val scanHandler    = Handler(Looper.getMainLooper())
    private var isScanning     = false
    private var scanRestartJob : Runnable? = null

    // ── Flows observed by UI ──────────────────────────────────────────────────
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices

    private val _receivedMessages  = MutableStateFlow<List<ChatMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ChatMessage>> = _receivedMessages

    // Separate flow for broadcast messages (plain text, shown in HomeScreen)
    private val _broadcastMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val broadcastMessages: StateFlow<List<ChatMessage>> = _broadcastMessages

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState

    // ── Online count derived from discovered devices ───────────────────────────
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
        if (gattServer != null) return // already set up

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
    // START ADVERTISING
    // ─────────────────────────────────────────────────────────────────────────
    fun startAdvertising(myUsername: String) {
        if (!hasPermissions() || !isBluetoothEnabled()) return

        bleAdvertiser          = bluetoothAdapter?.bluetoothLeAdvertiser
        bluetoothAdapter?.name = myUsername.take(20)
        myAddress              = bluetoothAdapter?.address ?: ""
        myFullUsername         = myUsername   // store for recipient matching

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
    //
    // Android imposes a ~30s scan limit before throttling. We work around it
    // by stopping and restarting every 25 seconds. The device list is kept
    // across restarts so contacts don't disappear during the brief gap.
    //
    // Devices that haven't been seen for 60s are removed from the list
    // (considered offline).
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
        // Stop any existing scan first to prevent SCAN_FAILED_ALREADY_STARTED (error code 1)
        try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}

        val filter   = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BLEConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)          // report results immediately, no batching
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)   // detect weak signals
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT) // first ad packet
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun scheduleRestart() {
        scanRestartJob?.let { scanHandler.removeCallbacks(it) }
        scanRestartJob = Runnable {
            if (!isScanning) return@Runnable
            if (hasPermissions()) bleScanner?.stopScan(scanCallback)
            // Reduced restart interval — Android throttles scans > 30s
            // 8s gives us fast discovery without throttle
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

    // Remove devices not seen in the last 60 seconds
    private fun pruneStaleDevices() {
        val cutoff = System.currentTimeMillis() - 45_000

        // Remove stale DIRECT devices
        val directOk = _discoveredDevices.value
            .filter { it.reachType == ReachType.DIRECT }
            .filter { it.lastSeen > cutoff }

        // Remove MESH devices whose relay is no longer directly reachable
        val directUsernames = directOk.map { it.username }.toSet()
        val meshOk = _discoveredDevices.value
            .filter { it.reachType == ReachType.MESH }
            .filter { it.viaDevice in directUsernames }  // relay still online?

        val updated = directOk + meshOk
        if (updated.size != _discoveredDevices.value.size) {
            _discoveredDevices.value = updated
        }
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

            // Skip devices without a valid username — they are not Bluetooth Chat devices
            if (deviceName.isBlank() || !deviceName.contains("#")) return

            // Use username ("Name#TAG") as unique display/routing ID
            // but keep real MAC address for GATT connection.
            val uniqueId  = deviceName          // "Sakib#C7BB" — stable, unique
            val macAddress = device.address     // real MAC — used only for connectGatt()

            val list  = _discoveredDevices.value.toMutableList()
            val index = list.indexOfFirst { it.username == uniqueId }
            val found = DiscoveredDevice(
                id             = uniqueId,      // username as routing/display ID
                macAddress     = macAddress,    // real MAC stored for connectGatt()
                username       = deviceName,
                displayName    = deviceName.substringBefore("#"),
                avatar         = "🧑",
                signalStrength = rssi,
                lastSeen       = System.currentTimeMillis()
            )
            val isNew = index < 0
            if (index >= 0) list[index] = found else list.add(found)
            _discoveredDevices.value = list

            // Auto-connect using real MAC address, not username
            if (isNew && !clientGatts.containsKey(macAddress)) {
                Log.d(BLEConstants.TAG, "Auto-connecting to: $deviceName ($macAddress)")
                connectToDevice(macAddress)   // ← real MAC address, not username
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(BLEConstants.TAG, "❌ Scan failed: $errorCode — restarting in 3s")
            // Auto-restart on failure
            if (isScanning) {
                scanHandler.postDelayed({ if (isScanning) doStartScan() }, 3_000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONNECT TO DEVICE
    // ─────────────────────────────────────────────────────────────────────────
    fun connectToDevice(deviceId: String) {
        if (!hasPermissions()) return
        if (clientGatts.containsKey(deviceId)) {
            Log.d(BLEConstants.TAG, "Already connected to $deviceId")
            return
        }
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
        if (clientGatts.isEmpty() && connectedClients.isEmpty()) {
            _connectionState.value = "Disconnected"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SEND MESSAGE (Mesh-aware)
    // ─────────────────────────────────────────────────────────────────────────
    fun sendMessage(
        encryptedText : String,
        myUsername    : String,
        recipientId   : String = MeshMessage.BROADCAST
    ) {
        if (!hasPermissions()) return

        val msg = MeshMessage(
            senderId         = myAddress,
            senderName       = myUsername,
            recipientId      = recipientId,
            encryptedContent = encryptedText
        )

        seenMessageIds.add(msg.id)
        transmitRaw(msg.toBytes(), skipAddress = null)
        Log.d(BLEConstants.TAG, "📤 Sent to $recipientId (hop 0)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ROUTE / RELAY
    // ─────────────────────────────────────────────────────────────────────────
    private fun routeMessage(msg: MeshMessage, receivedFromAddress: String) {
        if (msg.hop >= msg.maxHops) {
            Log.d(BLEConstants.TAG, "🛑 Max hops reached, dropping ${msg.id}")
            return
        }
        val relayed = msg.copy(hop = msg.hop + 1)
        Log.d(BLEConstants.TAG, "↪️ Relaying ${msg.id} hop ${relayed.hop}")
        transmitRaw(relayed.toBytes(), skipAddress = receivedFromAddress)
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
            Log.d(BLEConstants.TAG, "📤 CLIENT write → $address")
        }

        val serverChar = gattServer
            ?.getService(BLEConstants.SERVICE_UUID)
            ?.getCharacteristic(BLEConstants.MESSAGE_CHARACTERISTIC_UUID) ?: return

        serverChar.value = bytes
        connectedClients.forEach { device ->
            if (device.address == skipAddress) return@forEach
            gattServer?.notifyCharacteristicChanged(device, serverChar, false)
            Log.d(BLEConstants.TAG, "📤 SERVER notify → ${device.address}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRESENCE — tell new connection who else we know
    // ─────────────────────────────────────────────────────────────────────────
    private fun sendPresenceTo(gatt: android.bluetooth.BluetoothGatt) {
        if (!hasPermissions()) return
        // List of all directly-discovered devices (not mesh ones — avoid chain)
        val directPeers = _discoveredDevices.value
            .filter { it.reachType == ReachType.DIRECT }
            .map { it.username }

        if (directPeers.isEmpty()) return

        val presence = MeshMessage(
            senderId         = myFullUsername,
            senderName       = myFullUsername,
            recipientId      = MeshMessage.BROADCAST,
            encryptedContent = "",
            type             = MeshMessage.TYPE_PRESENCE,
            knownPeers       = directPeers
        )

        val bytes = presence.toBytes()
        val service = gatt.getService(BLEConstants.SERVICE_UUID) ?: return
        val char    = service.getCharacteristic(BLEConstants.MESSAGE_CHARACTERISTIC_UUID) ?: return
        try {
            @Suppress("DEPRECATION")
            char.value     = bytes
            char.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
            Log.d(BLEConstants.TAG, "📋 Sent presence to ${gatt.device.address} with peers: $directPeers")
        } catch (e: Exception) {
            Log.e(BLEConstants.TAG, "Presence send failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROCESS INCOMING MESSAGE
    // ─────────────────────────────────────────────────────────────────────────
    private fun processIncomingBytes(bytes: ByteArray, fromAddress: String) {
        val msg = MeshMessage.fromBytes(bytes) ?: run {
            // Cannot parse — packet likely truncated due to MTU limit
            // Silently drop rather than showing raw JSON/bytes to the user
            Log.w(BLEConstants.TAG, "⚠️ Could not parse message — packet may be truncated")
            return
        }

        if (msg.id in seenMessageIds) {
            Log.d(BLEConstants.TAG, "🔄 Duplicate dropped")
            return
        }
        seenMessageIds.add(msg.id)

        when {
            // ── Broadcast: always deliver + relay ─────────────────────────────
            // ── Presence message — update mesh-reachable contacts ──────────
            msg.type == MeshMessage.TYPE_PRESENCE -> {
                Log.d(BLEConstants.TAG, "📋 Presence from ${msg.senderName}: ${msg.knownPeers}")
                msg.knownPeers.forEach { peer ->
                    // Only add if not already directly discovered and not ourselves
                    if (peer != myFullUsername &&
                        _discoveredDevices.value.none { it.username == peer }) {
                        val displayName = peer.substringBefore("#")
                        val meshDevice  = DiscoveredDevice(
                            id        = peer,
                            username  = peer,
                            displayName = displayName,
                            avatar    = "🧑",
                            reachType = ReachType.MESH,
                            viaDevice = msg.senderName,
                            lastSeen  = System.currentTimeMillis()
                        )
                        val list = _discoveredDevices.value.toMutableList()
                        list.add(meshDevice)
                        _discoveredDevices.value = list
                        Log.d(BLEConstants.TAG, "🌐 Mesh device added: $peer via ${msg.senderName}")
                    }
                }
                return
            }

            msg.recipientId == MeshMessage.BROADCAST -> {
                Log.d(BLEConstants.TAG, "📢 Broadcast from ${msg.senderName}")
                appendBroadcastToUi(msg.encryptedContent, msg.id, msg.senderName)
                // Show notification for broadcast messages
                NotificationHelper.showBroadcastNotification(
                    context     = context,
                    senderName  = msg.senderName,
                    messageText = msg.encryptedContent
                )
                routeMessage(msg, receivedFromAddress = fromAddress)
                return
            }

            // ── Private message: check by username not MAC ────────────────────
            // MAC address is randomised to 02:00:00:00:00:00 on Android 6+
            // so we use the full username ("Name#TAG") as the recipient identifier.
            msg.recipientId == myFullUsername -> {
                Log.d(BLEConstants.TAG, "🔐 Private message for ME from ${msg.senderName}")
                appendToUi(msg.encryptedContent, msg.id)
                // Mark sender as unread — shows red dot on contact card
                UnreadStore.markUnread(context, msg.senderName)
                // Show notification — taps open HomeActivity Nearby tab
                val senderTag = msg.senderName.substringAfter("#", "")
                NotificationHelper.showPrivateMessageNotification(
                    context         = context,
                    senderName      = msg.senderName.substringBefore("#"),
                    senderAvatar    = "🧑",
                    contactFullName = msg.senderName,
                    deviceId        = fromAddress,
                    contactTag      = senderTag
                )
                return  // don't relay — message reached its destination
            }

            // ── Not for me: relay to other connected devices ──────────────────
            else -> {
                Log.d(BLEConstants.TAG, "🔁 Relaying message for ${msg.recipientId}")
                routeMessage(msg, receivedFromAddress = fromAddress)
                return
            }
        }

    }

    private fun appendToUi(encryptedContent: String, messageId: String? = null) {
        _receivedMessages.value += ChatMessage(
            id     = messageId ?: java.util.UUID.randomUUID().toString(),
            text   = encryptedContent,
            isMine = false,
            status = MessageStatus.DELIVERED
        )
    }

    private fun appendBroadcastToUi(text: String, messageId: String? = null, senderName: String = "") {
        _broadcastMessages.value += ChatMessage(
            id         = messageId ?: java.util.UUID.randomUUID().toString(),
            text       = text,
            isMine     = false,
            senderName = senderName,   // full "Name#TAG" passed from MeshMessage
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
                    if (hasPermissions()) {
                        // Request 512-byte MTU so full MeshMessage JSON fits in one packet
                        gatt.requestMtu(512)
                    }
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
            Log.d(BLEConstants.TAG, "MTU changed to $mtu on ${gatt.device.address}")
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
            Log.d(BLEConstants.TAG, "✅ Notifications enabled on ${gatt.device.address}")
            // Send presence so the other device knows who else we are connected to
            sendPresenceTo(gatt)
        }

        // Android 13+ (API 33+) — new signature with value param
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            processIncomingBytes(value, gatt.device.address)
        }

        // Android 12 and below — deprecated but required for OPPO CPH2667 (API 35)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            processIncomingBytes(characteristic.value ?: return, gatt.device.address)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS)
                Log.d(BLEConstants.TAG, "✅ Write confirmed to ${gatt.device.address}")
            else
                Log.e(BLEConstants.TAG, "❌ Write failed: $status")
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
                    Log.d(BLEConstants.TAG, "✅ Server: ${device.address} connected")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedClients.remove(device)
                    if (clientGatts.isEmpty() && connectedClients.isEmpty())
                        _connectionState.value = "Disconnected"
                    Log.d(BLEConstants.TAG, "🔌 Server: ${device.address} disconnected")
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
            Log.d(BLEConstants.TAG, "✅ ${device.address} enabled notifications")
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