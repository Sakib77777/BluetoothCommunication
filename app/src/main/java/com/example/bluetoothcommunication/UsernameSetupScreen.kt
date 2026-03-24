package com.example.bluetoothcommunication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Avatar Options ───────────────────────────────────────────────────────────
val avatarOptions = listOf("🧑", "👩", "🧔", "👨‍💻", "👩‍💻", "🥷", "🧑‍🚀", "👾")

// ─────────────────────────────────────────────────────────────────────────────
// DEVICE TAG
// ─────────────────────────────────────────────────────────────────────────────
fun getDeviceTag(context: Context): String {
    val androidId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )
    return androidId?.take(4)?.uppercase() ?: "XXXX"
}

fun buildFullUsername(name: String, context: Context): String =
    "${name.trim()}#${getDeviceTag(context)}"

// ─── Username Setup Activity ──────────────────────────────────────────────────
class UsernameSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BluetoothChatTheme {
                UsernameSetupScreen(
                    onContinue = { _, _ ->
                        startActivity(Intent(this, HomeActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

// ─── Username Setup Screen ────────────────────────────────────────────────────
@Composable
fun UsernameSetupScreen(onContinue: (String, String) -> Unit = { _, _ -> }) {

    val context      = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope        = rememberCoroutineScope()

    var username       by remember { mutableStateOf("") }
    var selectedAvatar by remember { mutableStateOf("🧑") }
    var bio            by remember { mutableStateOf("") }
    var errorMessage   by remember { mutableStateOf("") }
    var isSuccess      by remember { mutableStateOf(false) }
    var visible        by remember { mutableStateOf(false) }

    val deviceTag = remember { getDeviceTag(context) }

    val alpha by animateFloatAsState(
        targetValue   = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label         = "setup"
    )

    val shakeOffset = remember { Animatable(0f) }
    fun triggerShake() {
        scope.launch {
            repeat(4) { shakeOffset.animateTo(if (it % 2 == 0) 10f else -10f, tween(60)) }
            shakeOffset.animateTo(0f, tween(60))
        }
    }

    LaunchedEffect(Unit) { delay(100); visible = true }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .alpha(alpha)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // ── Header badge ──────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text     = "📡  Bluetooth Chat",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Title ─────────────────────────────────────────────────────────
            Column(
                modifier            = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text       = "Set Up Your Profile",
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text  = "Choose your avatar and a username to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Avatar preview ────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier
                    .size(88.dp)
                    .background(
                        brush = Brush.linearGradient(listOf(NeonPurple.copy(0.15f), NeonBlue.copy(0.15f))),
                        shape = CircleShape
                    )
                    .border(
                        width = 2.dp,
                        brush = Brush.linearGradient(listOf(NeonPurple, NeonBlue)),
                        shape = CircleShape
                    )
            ) {
                Text(text = selectedAvatar, fontSize = 42.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Avatar section label ──────────────────────────────────────────
            Text(
                text          = "CHOOSE AVATAR",
                style         = MaterialTheme.typography.labelSmall,
                color         = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                modifier      = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Avatar grid ───────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                avatarOptions.take(4).forEach { emoji ->
                    AvatarOption(
                        avatar     = emoji,
                        isSelected = selectedAvatar == emoji,
                        onClick    = { selectedAvatar = emoji }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                avatarOptions.drop(4).forEach { emoji ->
                    AvatarOption(
                        avatar     = emoji,
                        isSelected = selectedAvatar == emoji,
                        onClick    = { selectedAvatar = emoji }
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Username input ────────────────────────────────────────────────
            Text(
                text          = "USERNAME",
                style         = MaterialTheme.typography.labelSmall,
                color         = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                modifier      = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value         = username,
                onValueChange = {
                    if (it.length <= 20) { username = it; errorMessage = "" }
                },
                placeholder     = { Text("e.g. Sakib") },
                singleLine      = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { focusManager.clearFocus() }),
                trailingIcon    = {
                    Text(
                        text       = "#$deviceTag",
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier.padding(end = 14.dp)
                    )
                },
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = shakeOffset.value.dp),
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            // Error message
            AnimatedVisibility(
                visible = errorMessage.isNotEmpty(),
                enter   = fadeIn() + slideInVertically()
            ) {
                Text(
                    text     = "⚠️  $errorMessage",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Bio / status input (optional) ─────────────────────────────────
            Text(
                text          = "BIO (OPTIONAL)",
                style         = MaterialTheme.typography.labelSmall,
                color         = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                modifier      = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value         = bio,
                onValueChange = { if (it.length <= 60) bio = it },
                placeholder   = { Text("e.g. Love hiking 🏔️") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                trailingIcon = {
                    Text(
                        text     = "${bio.length}/60",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Live username preview ─────────────────────────────────────────
            AnimatedVisibility(
                visible = username.isNotEmpty() && !isSuccess,
                enter   = fadeIn() + slideInVertically()
            ) {
                Surface(
                    shape    = RoundedCornerShape(12.dp),
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier              = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(
                                text          = "YOUR UNIQUE ID",
                                style         = MaterialTheme.typography.labelSmall,
                                color         = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)) {
                                        append(username.trim())
                                    }
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) {
                                        append("#$deviceTag")
                                    }
                                },
                                fontSize = 20.sp
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SoftCyan.copy(0.15f)
                        ) {
                            Text(
                                text       = "✓ Unique",
                                style      = MaterialTheme.typography.labelSmall,
                                color      = SoftCyan,
                                fontWeight = FontWeight.SemiBold,
                                modifier   = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Get Started button ────────────────────────────────────────────
            Button(
                onClick = {
                    when {
                        username.isBlank() -> {
                            errorMessage = "Please enter a username"
                            triggerShake()
                        }
                        username.trim().length < 3 -> {
                            errorMessage = "Username must be at least 3 characters"
                            triggerShake()
                        }
                        !username.trim().matches(Regex("[a-zA-Z0-9_]+")) -> {
                            errorMessage = "Only letters, numbers and _ allowed"
                            triggerShake()
                        }
                        else -> {
                            isSuccess = true
                            val fullUsername = buildFullUsername(username, context)
                            context.getSharedPreferences("BluetoothChat", Context.MODE_PRIVATE)
                                .edit()
                                .putString("username",    fullUsername)
                                .putString("displayName", username.trim())
                                .putString("deviceTag",   deviceTag)
                                .putString("avatar",      selectedAvatar)
                                .putString("bio",         bio.trim())
                                .apply()
                            scope.launch {
                                delay(600)
                                onContinue(fullUsername, selectedAvatar)
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text       = if (isSuccess) "✓  All Set!" else "Get Started  →",
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text  = "🔒  Your unique ID is tied to this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Avatar Option ────────────────────────────────────────────────────────────
@Composable
fun AvatarOption(avatar: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) NeonPurple.copy(0.15f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .then(
                if (isSelected) Modifier.border(
                    width = 2.dp,
                    brush = Brush.linearGradient(listOf(NeonPurple, NeonBlue)),
                    shape = CircleShape
                ) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            .clickable { onClick() }
    ) {
        Text(text = avatar, fontSize = 22.sp)
    }
}
