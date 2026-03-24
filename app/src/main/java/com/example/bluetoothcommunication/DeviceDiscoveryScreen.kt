package com.example.bluetoothcommunication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── signalLabel / signalColor / signalBars helpers ────────────────────────────
fun signalLabel(rssi: Int): String = when {
    rssi >= -50 -> "Excellent"
    rssi >= -65 -> "Good"
    rssi >= -80 -> "Fair"
    else        -> "Weak"
}

@Composable
fun signalColor(rssi: Int): Color = when {
    rssi >= -50 -> SoftCyan
    rssi >= -65 -> NeonBlue
    rssi >= -80 -> Color(0xFFFFB347)
    else        -> MaterialTheme.colorScheme.error
}

fun signalBars(rssi: Int): Int = when {
    rssi >= -50 -> 4
    rssi >= -65 -> 3
    rssi >= -80 -> 2
    else        -> 1
}

// ─── Device Discovery Activity ────────────────────────────────────────────────
class DeviceDiscoveryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ── Use singleton manager — NOT a new instance ─────────────────────────
        val btManager  = (application as BluetoothChatApp).btManager
        val prefs      = getSharedPreferences("BluetoothChat", Context.MODE_PRIVATE)
        val myUsername = prefs.getString("username", "Unknown") ?: "Unknown"

        btManager.startAdvertising(myUsername)

        setContent {
            BluetoothChatTheme {
                val realDevices by btManager.discoveredDevices.collectAsState()
                val connState   by btManager.connectionState.collectAsState()

                DeviceDiscoveryScreen(
                    devices     = realDevices,
                    connState   = connState,
                    onStartScan = { btManager.startContinuousScanning() },
                    onStopScan  = { btManager.stopScanning() },
                    onConnect   = { device ->
                        // Use macAddress for BLE connection — device.id is username
                        if (device.macAddress.isNotBlank()) {
                            btManager.connectToDevice(device.macAddress)
                        }
                        startActivity(Intent(this, ChatActivity::class.java).apply {
                            putExtra("contactName",     device.displayName)
                            putExtra("contactAvatar",   device.avatar)
                            putExtra("contactTag",      device.username.substringAfter("#"))
                            putExtra("deviceId",        device.macAddress)  // MAC for BLE
                            putExtra("contactFullName", device.username)     // username for routing
                        })
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't call cleanup() — singleton is shared with HomeActivity/ChatActivity
        // Just stop scanning since we're leaving this screen
        (application as BluetoothChatApp).btManager.stopScanning()
    }
}

// ─── Device Discovery Screen ──────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiscoveryScreen(
    devices     : List<DiscoveredDevice> = emptyList(),
    connState   : String = "Disconnected",
    onStartScan : () -> Unit = {},
    onStopScan  : () -> Unit = {},
    onConnect   : (DiscoveredDevice) -> Unit = {},
    onBack      : () -> Unit = {}
) {
    var isScanning   by remember { mutableStateOf(false) }
    var connectingId by remember { mutableStateOf<String?>(null) }

    // Scanning dot blink
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val scanDot by infiniteTransition.animateFloat(
        initialValue  = 0.3f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label         = "dot"
    )

    // Auto-start scanning when screen opens
    LaunchedEffect(Unit) {
        isScanning = true
        onStartScan()
    }

    // Clear connecting state when connection established
    LaunchedEffect(connState) {
        if (connState == "Connected") connectingId = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Find Devices", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier         = Modifier
                            .padding(start = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onStopScan(); onBack() }
                    ) {
                        Text(text = "←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    // Scan toggle button
                    TextButton(
                        onClick = {
                            if (isScanning) { onStopScan(); isScanning = false }
                            else            { onStartScan(); isScanning = true }
                        }
                    ) {
                        if (isScanning) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(7.dp).alpha(scanDot).background(SoftCyan, CircleShape))
                                Text(text = "Scanning", color = SoftCyan, style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Text(text = "Scan", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Status bar ────────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape    = RoundedCornerShape(8.dp),
                color    = when (connState) {
                    "Connected"   -> SoftCyan.copy(0.1f)
                    "Connecting..." -> NeonBlue.copy(0.1f)
                    else          -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text     = when (connState) {
                        "Connected"    -> "✅  Connected!"
                        "Connecting..."-> "⏳  Connecting..."
                        else           -> if (isScanning) "🔍  Scanning for nearby devices..."
                        else             "Tap Scan to search"
                    },
                    style    = MaterialTheme.typography.bodySmall,
                    color    = when (connState) {
                        "Connected" -> SoftCyan
                        else        -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            // ── Devices found label ───────────────────────────────────────────
            if (devices.isNotEmpty()) {
                Text(
                    text     = "${devices.size} device${if (devices.size > 1) "s" else ""} found",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // ── Content ───────────────────────────────────────────────────────
            if (devices.isEmpty()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier            = Modifier.padding(32.dp)
                    ) {
                        Text(text = if (isScanning) "📡" else "🔍", fontSize = 52.sp)
                        Text(
                            text       = if (isScanning) "Looking for devices..." else "No devices found",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color      = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text      = "Make sure the other phone has\nBluetooth Chat open and running",
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier            = Modifier.weight(1f),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(devices, key = { it.id }) { device ->
                        DiscoveredDeviceItem(
                            device       = device,
                            isConnecting = connectingId == device.id,
                            isConnected  = connState == "Connected" && connectingId == device.id,
                            onConnect    = {
                                connectingId = device.id
                                onConnect(device)
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// ─── Discovered Device Item ───────────────────────────────────────────────────
@Composable
fun DiscoveredDeviceItem(
    device       : DiscoveredDevice,
    isConnecting : Boolean,
    isConnected  : Boolean,
    onConnect    : () -> Unit
) {
    val bars  = signalBars(device.signalStrength)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border   = BorderStroke(
            1.dp,
            when {
                isConnected  -> SoftCyan.copy(0.5f)
                isConnecting -> NeonBlue.copy(0.5f)
                else         -> MaterialTheme.colorScheme.outline
            }
        )
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier              = Modifier.padding(16.dp)
        ) {
            // Avatar
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(NeonPurple.copy(0.15f), NeonBlue.copy(0.15f))),
                        shape = CircleShape
                    )
            ) {
                Text(text = device.avatar, fontSize = 22.sp)
            }

            // Name and signal
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text       = device.displayName,
                        style      = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text  = "#${device.username.substringAfter("#")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(0.7f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SignalBars(rssi = device.signalStrength)
                    Text(
                        text  = "${signalLabel(device.signalStrength)} · ${device.signalStrength} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = signalColor(device.signalStrength)
                    )
                }
            }

            // Connect button
            Button(
                onClick        = onConnect,
                enabled        = !isConnecting && !isConnected,
                shape          = RoundedCornerShape(10.dp),
                colors         = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isConnected  -> SoftCyan.copy(0.2f)
                        isConnecting -> NeonBlue.copy(0.2f)
                        else         -> MaterialTheme.colorScheme.primary
                    },
                    contentColor = when {
                        isConnected  -> SoftCyan
                        isConnecting -> NeonBlue
                        else         -> MaterialTheme.colorScheme.onPrimary
                    },
                    disabledContainerColor = when {
                        isConnected  -> SoftCyan.copy(0.2f)
                        else         -> NeonBlue.copy(0.2f)
                    },
                    disabledContentColor = when {
                        isConnected  -> SoftCyan
                        else         -> NeonBlue
                    }
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text  = when {
                        isConnected  -> "✓ Done"
                        isConnecting -> "..."
                        else         -> "Connect"
                    },
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─── Signal Bars ──────────────────────────────────────────────────────────────
@Composable
fun SignalBars(rssi: Int) {
    val bars  = signalBars(rssi)
    val color = signalColor(rssi)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5, 9, 13, 17).forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < bars) color else MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}