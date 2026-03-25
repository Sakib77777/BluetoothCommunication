package com.example.bluetoothcommunication

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────
data class ChatMessage(
    val id          : String = UUID.randomUUID().toString(),
    val text        : String,
    val isMine      : Boolean,
    val senderName  : String = "",
    val time        : String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val isEncrypted : Boolean = true,
    val status      : MessageStatus = MessageStatus.SENT
)

enum class MessageStatus { SENDING, SENT, DELIVERED, READ }

// ─────────────────────────────────────────────────────────────────────────────
// CHAT STORAGE
// ─────────────────────────────────────────────────────────────────────────────
object ChatStorage {

    private const val PREFS_NAME = "ChatHistory"
    private fun key(contactId: String) = "chat_${contactId.replace(":", "_")}"

    fun save(context: Context, contactId: String, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.takeLast(200).forEach { msg ->
            arr.put(JSONObject().apply {
                put("id",     msg.id)
                put("text",   msg.text)
                put("mine",   msg.isMine)
                put("sender", msg.senderName)
                put("time",   msg.time)
                put("status", msg.status.name)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key(contactId), arr.toString()).apply()
    }

    fun load(context: Context, contactId: String): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(contactId), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(
                    id         = o.getString("id"),
                    text       = o.getString("text"),
                    isMine     = o.getBoolean("mine"),
                    senderName = o.optString("sender", ""),
                    time       = o.getString("time"),
                    status     = runCatching {
                        // Saved SENDING messages are treated as SENT on reload
                        val s = MessageStatus.valueOf(o.getString("status"))
                        if (s == MessageStatus.SENDING) MessageStatus.SENT else s
                    }.getOrDefault(MessageStatus.DELIVERED)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context, contactId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(key(contactId)).apply()
    }
}

// ─── Chat Activity ────────────────────────────────────────────────────────────
class ChatActivity : ComponentActivity() {

    private val currentContactFullName = mutableStateOf("")
    private val currentContactName     = mutableStateOf("")
    private val currentContactAvatar   = mutableStateOf("🧑")
    private val currentContactTag      = mutableStateOf("????")
    private val currentDeviceId        = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loadFromIntent(intent)

        val btManager  = (application as BluetoothChatApp).btManager
        val prefs      = getSharedPreferences("BluetoothChat", Context.MODE_PRIVATE)
        val myName     = prefs.getString("displayName", "Me") ?: "Me"
        val myDevTag   = prefs.getString("deviceTag", "") ?: ""
        val myFullName = prefs.getString("username", "$myName#$myDevTag") ?: "$myName#$myDevTag"

        setContent {
            BluetoothChatTheme {
                val contactFullName = currentContactFullName.value
                val contactName     = currentContactName.value
                val contactAvatar   = currentContactAvatar.value
                val contactTag      = currentContactTag.value
                val deviceId        = currentDeviceId.value

                val encryptionKey = remember(contactFullName) {
                    EncryptionManager.generateSharedKey(myFullName, contactFullName)
                }

                val savedMessages = remember(contactFullName) {
                    if (contactFullName.isNotBlank()) ChatStorage.load(this@ChatActivity, contactFullName)
                    else emptyList()
                }

                LaunchedEffect(deviceId) {
                    if (deviceId.isNotBlank() && deviceId.contains(":")) {
                        btManager.connectToDevice(deviceId)
                    }
                }

                val btMessages    by btManager.receivedMessages.collectAsState()
                val deliveredAcks by btManager.deliveredAcks.collectAsState()
                val readReceipts  by btManager.readReceipts.collectAsState()

                key(contactFullName) {
                    ChatScreen(
                        contactName      = contactName,
                        contactAvatar    = contactAvatar,
                        contactTag       = contactTag,
                        contactId        = contactFullName,
                        myFullName       = myFullName,
                        myName           = myName,
                        btMessages       = btMessages,
                        encryptionKey    = encryptionKey,
                        deliveredAcks    = deliveredAcks,
                        readReceipts     = readReceipts,
                        onSendMessage    = { text, msgId ->
                            val encrypted = EncryptionManager.encrypt(text, encryptionKey)
                            btManager.sendMessage(encrypted, myName, recipientId = contactFullName, messageId = msgId)
                        },
                        onSendReadReceipt = { msgId ->
                            btManager.sendReadReceipt(msgId, toUsername = contactFullName)
                        },
                        onBackClick      = { finish() },
                        savedMessages    = savedMessages,
                        storageKey       = contactFullName,
                        appContext       = this
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: android.content.Intent) {
        val tag = intent.getStringExtra("contactTag") ?: "????"
        currentContactFullName.value = intent.getStringExtra("contactFullName") ?: ""
        currentContactName.value     = intent.getStringExtra("contactName")     ?: "Unknown"
        currentContactAvatar.value   = intent.getStringExtra("contactAvatar")   ?: "🧑"
        currentContactTag.value      = tag
        currentDeviceId.value        = intent.getStringExtra("deviceId")        ?: ""
    }
}

// ─── Chat Screen ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactName       : String = "Rahul",
    contactAvatar     : String = "👨‍💻",
    contactTag        : String = "A3F7",
    contactId         : String = "",
    myFullName        : String = "",
    myName            : String = "Me",
    btMessages        : List<ChatMessage> = emptyList(),
    encryptionKey     : javax.crypto.SecretKey? = null,
    deliveredAcks     : String = "",    // emits msgId when BLE write confirmed
    readReceipts      : String = "",    // emits msgId when recipient reads it
    onSendMessage     : (text: String, msgId: String) -> Unit = { _, _ -> },
    onSendReadReceipt : (msgId: String) -> Unit = {},
    onBackClick       : () -> Unit = {},
    savedMessages     : List<ChatMessage> = emptyList(),
    storageKey        : String = contactId,
    appContext        : Context? = null
) {
    val context   = appContext ?: LocalContext.current
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messageText     by remember { mutableStateOf("") }
    var isTyping        by remember { mutableStateOf(false) }
    var showEncBadge    by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val seenBleIds      = remember { mutableSetOf<String>() }

    var messages by remember {
        mutableStateOf(
            if (savedMessages.isNotEmpty()) savedMessages
            else if (storageKey.isNotEmpty()) ChatStorage.load(context, storageKey)
            else emptyList()
        )
    }

    LaunchedEffect(Unit) {
        messages.forEach { seenBleIds.add(it.id) }
    }

    LaunchedEffect(messages) {
        if (storageKey.isNotEmpty() && messages.isNotEmpty()) {
            ChatStorage.save(context, storageKey, messages)
        }
    }

    // ── DELIVERY ACK — BLE write confirmed → SENDING → SENT ──────────────────
    // deliveredAcks emits a new non-empty string each time a write succeeds
    LaunchedEffect(deliveredAcks) {
        if (deliveredAcks.isBlank()) return@LaunchedEffect
        messages = messages.map { msg ->
            if (msg.id == deliveredAcks && msg.status == MessageStatus.SENDING)
                msg.copy(status = MessageStatus.SENT)
            else msg
        }
        Log.d("ChatScreen", "✅ SENT confirmed for $deliveredAcks")
    }

    // ── READ RECEIPT — recipient opened chat → SENT/DELIVERED → READ ─────────
    LaunchedEffect(readReceipts) {
        if (readReceipts.isBlank()) return@LaunchedEffect
        messages = messages.map { msg ->
            if (msg.id == readReceipts && msg.isMine &&
                (msg.status == MessageStatus.SENT || msg.status == MessageStatus.DELIVERED))
                msg.copy(status = MessageStatus.READ)
            else msg
        }
        Log.d("ChatScreen", "👁 READ confirmed for $readReceipts")
    }

    val infiniteTransition = rememberInfiniteTransition(label = "chat")
    val btPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "bt"
    )
    val dot1 by infiniteTransition.animateFloat(initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse, StartOffset(0)),   label = "d1")
    val dot2 by infiniteTransition.animateFloat(initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse, StartOffset(150)), label = "d2")
    val dot3 by infiniteTransition.animateFloat(initialValue = 0.3f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse, StartOffset(300)), label = "d3")

    // ── Receive BLE message ───────────────────────────────────────────────────
    LaunchedEffect(btMessages) {
        val latest = btMessages.lastOrNull() ?: return@LaunchedEffect
        if (latest.id in seenBleIds) return@LaunchedEffect
        seenBleIds.add(latest.id)

        val decrypted = if (encryptionKey != null) {
            EncryptionManager.safeDecrypt(latest.text, encryptionKey) ?: return@LaunchedEffect
        } else latest.text

        isTyping = true
        delay(600)
        isTyping = false
        val incomingMsg = latest.copy(text = decrypted, status = MessageStatus.DELIVERED)
        messages = messages + incomingMsg
        delay(100)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)

        // Send read receipt so sender's ✓✓ turns blue
        onSendReadReceipt(latest.id)
    }

    LaunchedEffect(Unit) {
        delay(200)
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
        delay(300)
        showEncBadge = true
        delay(3000)
        showEncBadge = false
    }

    fun sendMessage() {
        if (messageText.isBlank()) return
        val msgId      = UUID.randomUUID().toString()
        val newMsg     = ChatMessage(id = msgId, text = messageText.trim(), isMine = true, status = MessageStatus.SENDING)
        val textToSend = messageText.trim()
        messages    = messages + newMsg
        messageText = ""
        scope.launch {
            delay(100)
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
            // Pass the same ID down so BluetoothChatManager creates the MeshMessage with it
            onSendMessage(textToSend, msgId)
        }
    }

    Scaffold(
        modifier       = Modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).background(brush = Brush.linearGradient(listOf(NeonPurple, NeonBlue)), shape = CircleShape)) {
                            Text(text = contactAvatar, fontSize = 16.sp)
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = contactName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Text(text = "#$contactTag", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(0.7f))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(modifier = Modifier.size(5.dp).alpha(btPulse).background(SoftCyan, CircleShape))
                                Text(text = "Connected via Bluetooth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                navigationIcon = {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(start = 8.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onBackClick() }) {
                        Text(text = "←", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    Surface(shape = RoundedCornerShape(6.dp), color = SoftCyan.copy(0.1f)) {
                        Text(text = "🔒 E2E", style = MaterialTheme.typography.labelSmall, color = SoftCyan, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(end = 8.dp).size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { showClearDialog = true }) {
                        Text(text = "⋮", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier              = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    OutlinedTextField(
                        value           = messageText,
                        onValueChange   = { messageText = it },
                        placeholder     = { Text("Type a message...", style = MaterialTheme.typography.bodyMedium) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                        shape           = RoundedCornerShape(24.dp),
                        modifier        = Modifier.weight(1f),
                        maxLines        = 4,
                        colors          = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor      = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor    = MaterialTheme.colorScheme.outline,
                            focusedContainerColor   = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier         = Modifier
                            .size(48.dp)
                            .background(
                                brush = if (messageText.isNotBlank()) Brush.linearGradient(listOf(NeonPurple, NeonBlue))
                                else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)),
                                shape = CircleShape
                            )
                            .clickable(enabled = messageText.isNotBlank()) { sendMessage() }
                    ) {
                        Text(text = "➤", fontSize = 18.sp, color = if (messageText.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            AnimatedVisibility(visible = showEncBadge, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                Surface(color = SoftCyan.copy(0.08f)) {
                    Text(text = "🔒  Messages are end-to-end encrypted", style = MaterialTheme.typography.labelSmall, color = SoftCyan, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
            }

            if (messages.isEmpty() && !isTyping) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "🔒", fontSize = 36.sp)
                        Text(text = "No messages yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                        Text(text = "Say hi to $contactName!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    if (isTyping) {
                        item { TypingIndicator(avatar = contactAvatar, dot1 = dot1, dot2 = dot2, dot3 = dot3) }
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title   = { Text(text = "Clear chat history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
            text    = { Text(text = "This will permanently delete all messages with $contactName.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    messages = emptyList()
                    if (contactId.isNotEmpty()) ChatStorage.clear(context, storageKey)
                    showClearDialog = false
                }) {
                    Text(text = "Clear", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ─── Message Bubble ───────────────────────────────────────────────────────────
@Composable
fun MessageBubble(message: ChatMessage) {
    val bubbleShape = RoundedCornerShape(
        topStart    = 18.dp,
        topEnd      = 18.dp,
        bottomStart = if (message.isMine) 18.dp else 4.dp,
        bottomEnd   = if (message.isMine) 4.dp  else 18.dp
    )
    Row(
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        modifier              = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(
                    if (message.isMine)
                        Brush.linearGradient(listOf(NeonPurple, NeonBlue))
                    else
                        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
                )
                .then(if (!message.isMine) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, bubbleShape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text      = message.text,
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = if (message.isMine) Color.White else MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                // ── Timestamp + delivery status row ───────────────────────────
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment     = Alignment.CenterVertically,
                    modifier              = Modifier.fillMaxWidth()
                ) {
                    Text(text = "🔒", fontSize = 9.sp, modifier = Modifier.alpha(0.6f))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text  = message.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isMine) Color.White.copy(0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // ── Delivery status indicator (only on my messages) ────────
                    if (message.isMine) {
                        Spacer(modifier = Modifier.width(5.dp))
                        DeliveryStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

// ─── Delivery Status Icon ─────────────────────────────────────────────────────
// ○   SENDING   — clock / pending (message not yet written to BLE)
// ✓   SENT      — single tick (BLE write confirmed by onCharacteristicWrite)
// ✓✓  DELIVERED — double tick white (reached recipient's device)
// ✓✓  READ      — double tick CYAN (recipient opened the chat)
@Composable
fun DeliveryStatusIcon(status: MessageStatus) {
    when (status) {
        MessageStatus.SENDING -> {
            // Animated clock pulse while waiting for BLE write callback
            val infiniteTransition = rememberInfiniteTransition(label = "sending")
            val alpha by infiniteTransition.animateFloat(
                initialValue  = 0.3f,
                targetValue   = 1f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label         = "pulse"
            )
            Text(
                text     = "○",
                fontSize = 11.sp,
                color    = Color.White.copy(alpha),
                modifier = Modifier.alpha(alpha)
            )
        }
        MessageStatus.SENT -> {
            // Single tick — BLE write succeeded
            Text(
                text     = "✓",
                fontSize = 11.sp,
                color    = Color.White.copy(0.7f)
            )
        }
        MessageStatus.DELIVERED -> {
            // Double tick white — message arrived on recipient's device
            Text(
                text     = "✓✓",
                fontSize = 11.sp,
                color    = Color.White.copy(0.7f)
            )
        }
        MessageStatus.READ -> {
            // Double tick cyan — recipient opened the chat and read it
            Text(
                text       = "✓✓",
                fontSize   = 11.sp,
                color      = SoftCyan,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ─── Typing Indicator ─────────────────────────────────────────────────────────
@Composable
fun TypingIndicator(avatar: String, dot1: Float, dot2: Float, dot3: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Text(text = avatar, fontSize = 14.sp)
        }
        Surface(
            shape  = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            color  = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(modifier = Modifier.size(7.dp).alpha(dot1).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Box(modifier = Modifier.size(7.dp).alpha(dot2).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
                Box(modifier = Modifier.size(7.dp).alpha(dot3).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

// Add this import at the top of the file (android.util.Log for debug logging)
private fun Log.d(tag: String, msg: String) = android.util.Log.d(tag, msg)