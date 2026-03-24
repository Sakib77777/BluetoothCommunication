package com.example.bluetoothcommunication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ─── Splash Activity ──────────────────────────────────────────────────────────
class SplashActivity : ComponentActivity() {

    // All permissions requested at startup in one clean flow
    private val permissionsToRequest: Array<String> by lazy {
        buildList {
            // BLE permissions — Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                // Legacy BLE needs location
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Notification permission — Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    // Tracks if settings dialog should show
    private var showSettingsDialog = mutableStateOf(false)

    // Single launcher that requests ALL permissions at once
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            // Some permissions denied — show dialog to guide user to settings
            showSettingsDialog.value = true
        } else {
            enableBluetooth()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check which permissions still need to be granted
        val missing = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            enableBluetooth()
        }

        setContent {
            BluetoothChatTheme {
                // Show settings dialog if permissions were denied
                val showDialog by showSettingsDialog

                if (showDialog) {
                    PermissionSettingsDialog(
                        onOpenSettings = {
                            showSettingsDialog.value = false
                            // Open app settings so user can manually grant
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        },
                        onDismiss = {
                            showSettingsDialog.value = false
                            // Continue anyway — app will work partially
                            enableBluetooth()
                        }
                    )
                }

                SplashScreenUI(
                    onFinished = {
                        val prefs    = getSharedPreferences("BluetoothChat", MODE_PRIVATE)
                        val username = prefs.getString("username", null)
                        startActivity(
                            Intent(
                                this,
                                if (username != null) HomeActivity::class.java
                                else                  UsernameSetupActivity::class.java
                            )
                        )
                        finish()
                    }
                )
            }
        }
    }

    private fun enableBluetooth() {
        try {
            val bt = BluetoothAdapter.getDefaultAdapter()
            if (bt != null && !bt.isEnabled) {
                val permHandler = PermissionHandler(this)
                permHandler.requestEnableBluetooth()
            }
        } catch (_: Exception) {}
    }
}

// ─── Permission Settings Dialog ───────────────────────────────────────────────
@Composable
fun PermissionSettingsDialog(
    onOpenSettings : () -> Unit,
    onDismiss      : () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text       = "Permissions Required",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text  = "Bluetooth Chat needs these permissions to work:" +
                        "\n\n📶  Nearby Devices — to scan and connect" +
                        "\n🔔  Notifications — for message alerts" +
                        "\n\nPlease grant them in Settings.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue Anyway", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

// ─── Splash Screen UI ─────────────────────────────────────────────────────────
@Composable
fun SplashScreenUI(onFinished: () -> Unit = {}) {

    val context = LocalContext.current

    // ── FIX: Load mipmap as Bitmap — painterResource() crashes on adaptive icons
    val appIcon = remember {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
    }

    var visible by remember { mutableStateOf(false) }
    val alpha   by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(600),
        label         = "splash"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.3f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label         = "dot"
    )

    LaunchedEffect(Unit) {
        delay(200)
        visible = true
        delay(2200)
        onFinished()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier            = Modifier.alpha(alpha)
        ) {
            // ── App icon ──────────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier
                    .size(100.dp)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(NeonPurple, NeonBlue)
                        ),
                        shape = CircleShape
                    )
                    .padding(4.dp)
                    .clip(CircleShape)
            ) {
                if (appIcon != null) {
                    Image(
                        bitmap             = appIcon.asImageBitmap(),
                        contentDescription = "App logo",
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                    )
                } else {
                    // Fallback if bitmap fails to load
                    Text(text = "💬", fontSize = 38.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text       = "Bluetooth Chat",
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text      = "Mesh-powered secure messaging",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeatureBadge(icon = "📡", label = "Bluetooth")
                FeatureBadge(icon = "🔒", label = "Encrypted")
                FeatureBadge(icon = "🌐", label = "Mesh")
            }

            Spacer(modifier = Modifier.height(52.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(dotAlpha)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Text(
                    text  = "Starting up...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Feature Badge ────────────────────────────────────────────────────────────
@Composable
fun FeatureBadge(icon: String, label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier              = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(text = icon, fontSize = 13.sp)
            Text(
                text  = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
