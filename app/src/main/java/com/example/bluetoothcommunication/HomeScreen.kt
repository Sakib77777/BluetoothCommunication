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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// CONTACT STORE — persists seen contacts so they show as offline after scan ends
// ─────────────────────────────────────────────────────────────────────────────
object ContactStore {
    private const val PREFS = "BluetoothChatContacts"
    private const val KEY   = "contacts_json"

    fun save(context: Context, contacts: List<ChatContact>) {
        val arr = JSONArray()
        // Deduplicate by id before saving — prevents duplicate cards on reload
        contacts.distinctBy { it.id }.forEach { c ->
            arr.put(JSONObject().apply {
                put("id",     c.id)
                put("user",   c.username)
                put("name",   c.displayName)
                put("avatar", c.avatar)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<ChatContact> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { o ->
                    ChatContact(
                        id          = o.getString("id"),
                        username    = o.getString("user"),
                        displayName = o.getString("name"),
                        avatar      = o.getString("avatar"),
                        lastMessage = "Last seen nearby",
                        isOnline    = false,
                        reachType   = ReachType.OFFLINE
                    )
                }
            }.distinctBy { it.id }  // deduplicate on load — cleans up old bad data
        }.getOrElse { emptyList() }
    }

    // Call once to wipe corrupted/duplicate stored contacts
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MET CONTACTS STORE — tracks which contacts have already seen profile dialog
// First tap  → show profile popup
// Later taps → go straight to chat
// ─────────────────────────────────────────────────────────────────────────────
object MetContactsStore {
    private const val PREFS = "BluetoothChatMet"
    private const val KEY   = "met_ids"

    fun hasMet(context: Context, contactId: String): Boolean {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()) ?: emptySet()
        return contactId in set
    }

    fun markAsMet(context: Context, contactId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set   = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        set.add(contactId)
        prefs.edit().putStringSet(KEY, set).apply()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UNREAD STORE — tracks which contacts have unread messages
// Shows a green dot on contact card, cleared when user opens the chat
// ─────────────────────────────────────────────────────────────────────────────
object UnreadStore {
    private const val PREFS = "BluetoothChatUnread"

    // Store count with contact username as key directly (no encoding needed)
    // Use putString map so we can retrieve original username safely
    private fun countKey(contactFullName: String) = "cnt::$contactFullName"

    fun markUnread(context: android.content.Context, contactFullName: String) {
        val prefs   = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val current = prefs.getInt(countKey(contactFullName), 0)
        prefs.edit().putInt(countKey(contactFullName), current + 1).apply()
    }

    fun markRead(context: android.content.Context, contactFullName: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putInt(countKey(contactFullName), 0).apply()
    }

    fun getCount(context: android.content.Context, contactFullName: String): Int {
        return context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getInt(countKey(contactFullName), 0)
    }

    fun getAllCounts(context: android.content.Context): Map<String, Int> {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        return prefs.all
            .filter { it.key.startsWith("cnt::") && (it.value as? Int ?: 0) > 0 }
            .mapKeys  { it.key.removePrefix("cnt::") }   // safe — "::" never in username
            .mapValues { it.value as Int }
    }

    fun getUnread(context: android.content.Context): Set<String> = getAllCounts(context).keys
}

// ─── Home Activity ────────────────────────────────────────────────────────────
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val btManager = (application as BluetoothChatApp).btManager
        btManager.setupGattServer()

        val prefs     = getSharedPreferences("BluetoothChat", Context.MODE_PRIVATE)
        val username  = prefs.getString("displayName", "User") ?: "User"
        val avatar    = prefs.getString("avatar", "🧑") ?: "🧑"
        val deviceTag = prefs.getString("deviceTag", "????") ?: "????"
        val fullName  = prefs.getString("username", "$username#$deviceTag") ?: "$username#$deviceTag"

        btManager.startAdvertising(fullName)
        btManager.startContinuousScanning()

        // One-time cleanup of old duplicate data from previous app versions
        // Checks a version flag — only runs once after update
        val appPrefs = getSharedPreferences("BluetoothChatAppState", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("contacts_deduped_v2", false)) {
            ContactStore.clear(this)   // wipe old duplicate-corrupted data
            appPrefs.edit().putBoolean("contacts_deduped_v2", true).apply()
        }

        // Load persisted contacts (will appear as offline until scan finds them)
        val storedContacts = ContactStore.load(this)

        // Activity-level state so onNewIntent can update them reactively
        val activeTab      = mutableStateOf(intent.getIntExtra("openTab", 0))
        val unreadContacts = mutableStateOf(UnreadStore.getAllCounts(this))

        // Store refs so onNewIntent can update them
        activeTabState      = activeTab
        unreadContactsState = unreadContacts

        // Process initial notification intent
        intent.getStringExtra("unreadContact")?.takeIf { it.isNotBlank() }?.let {
            UnreadStore.markUnread(this, it)
            unreadContacts.value = UnreadStore.getAllCounts(this)
        }

        setContent {
            BluetoothChatTheme {
                val bleDevices   by btManager.discoveredDevices.collectAsState()
                val btMessages   by btManager.broadcastMessages.collectAsState()
                val btPrivate    by btManager.receivedMessages.collectAsState()

                // Read reactive state — updates when notification arrives
                val currentTab    by activeTab
                val currentUnread: Map<String, Int> by unreadContacts

                // Refresh unread counts whenever a new private message arrives
                // This makes the badge update in real-time even when app is open
                LaunchedEffect(btPrivate.size) {
                    unreadContacts.value = UnreadStore.getAllCounts(this@HomeActivity)
                }

                // Merge live scan results with stored contacts
                val contacts = remember(bleDevices) {
                    // Use LinkedHashMap with username as key — guaranteed no duplicates
                    val map = linkedMapOf<String, ChatContact>()

                    // First add stored (offline) — deduplicated by id
                    storedContacts.distinctBy { it.id }.forEach {
                        map[it.id] = it.copy(isOnline = false)
                    }

                    // Direct BLE devices (ONLINE)
                    bleDevices.filter { it.reachType == ReachType.DIRECT }
                        .distinctBy { it.id }.forEach { device ->
                            map[device.id] = ChatContact(
                                id          = device.id,
                                username    = device.username,
                                displayName = device.displayName,
                                avatar      = device.avatar,
                                lastMessage = "Tap to chat",
                                time        = "Now",
                                isOnline    = true,
                                macAddress  = device.macAddress,
                                reachType   = ReachType.DIRECT
                            )
                        }

                    // Mesh-reachable devices — discovered via relay
                    bleDevices.filter { it.reachType == ReachType.MESH }
                        .distinctBy { it.id }.forEach { device ->
                            if (!map.containsKey(device.id)) {  // don't override direct
                                map[device.id] = ChatContact(
                                    id          = device.id,
                                    username    = device.username,
                                    displayName = device.displayName,
                                    avatar      = device.avatar,
                                    lastMessage = "Reachable via ${device.viaDevice.substringBefore("#")}",
                                    time        = "Now",
                                    isOnline    = false,
                                    reachType   = ReachType.MESH,
                                    viaDevice   = device.viaDevice
                                )
                            }
                        }

                    map.values.toList().distinctBy { it.id }
                }

                // Save merged list for next app launch
                LaunchedEffect(contacts) {
                    ContactStore.save(this@HomeActivity, contacts)
                }

                HomeScreen(
                    context         = this,
                    myName          = username,
                    myAvatar        = avatar,
                    myTag           = deviceTag,
                    myFullUsername  = fullName,
                    contacts        = contacts,
                    btMessages      = btMessages,
                    initialTab      = currentTab,
                    unreadContacts  = currentUnread,
                    onSendBroadcast = { text ->
                        btManager.sendMessage(text, fullName, recipientId = MeshMessage.BROADCAST)
                    },
                    onChatClick = { contact ->
                        // Clear unread dot when opening chat
                        UnreadStore.markRead(this, contact.username)
                        unreadContacts.value = UnreadStore.getAllCounts(this)

                        if (contact.macAddress.isNotBlank()) {
                            btManager.connectToDevice(contact.macAddress)
                        }
                        startActivity(Intent(this, ChatActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("contactName",     contact.displayName)
                            putExtra("contactAvatar",   contact.avatar)
                            putExtra("contactTag",      contact.username.substringAfter("#"))
                            putExtra("deviceId",        contact.macAddress)
                            putExtra("contactFullName", contact.username)
                        })
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationHelper.cancelAll(this)
        // Refresh unread state — picks up marks from BluetoothChatManager
        unreadContactsState?.value = UnreadStore.getAllCounts(this)
    }

    // Activity-level state refs — set in onCreate, updated here
    private var activeTabState     : androidx.compose.runtime.MutableState<Int>?      = null
    private var unreadContactsState: androidx.compose.runtime.MutableState<Map<String, Int>>? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // Switch to Nearby tab when notification tapped
        val tab = intent.getIntExtra("openTab", -1)
        if (tab >= 0) activeTabState?.value = tab

        // Mark unread contact — red dot appears on contact card
        intent.getStringExtra("unreadContact")?.takeIf { it.isNotBlank() }?.let {
            UnreadStore.markUnread(this, it)
            unreadContactsState?.value = UnreadStore.getAllCounts(this)
        }
    }
}

// ─── Broadcast Message ────────────────────────────────────────────────────────
data class BroadcastMessage(
    val id           : String = UUID.randomUUID().toString(),
    val senderName   : String,
    val senderAvatar : String,
    val text         : String,
    val time         : String  = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
    val isMine       : Boolean = false
)

// ─── Home Screen ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    context         : Context? = null,
    myName          : String,
    myAvatar        : String,
    myTag           : String,
    myFullUsername  : String = "$myName#$myTag",
    contacts        : List<ChatContact>,
    btMessages      : List<ChatMessage> = emptyList(),
    initialTab      : Int = 0,
    unreadContacts  : Map<String, Int> = emptyMap(),
    onSendBroadcast : (String) -> Unit = {},
    onChatClick     : (ChatContact) -> Unit
) {
    val ctx = context ?: LocalContext.current

    var showProfileDialog by remember { mutableStateOf(false) }
    var selectedTab       by remember { mutableIntStateOf(initialTab) }
    var selectedContact   by remember { mutableStateOf<ChatContact?>(null) }

    // Switch tab when notification arrives while app is already open
    LaunchedEffect(initialTab) {
        selectedTab = initialTab
    }

    val onlineCount = contacts.count { it.isOnline }

    val infiniteTransition = rememberInfiniteTransition(label = "bt")
    val btBlink by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label         = "blink"
    )

    Scaffold(
        containerColor      = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bluetooth Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(6.dp).alpha(btBlink).background(SoftCyan, CircleShape))
                            Text(
                                text  = if (onlineCount == 0) "Scanning..." else "$onlineCount online nearby",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (onlineCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else SoftCyan
                            )
                        }
                    }
                },
                actions = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier         = Modifier
                            .padding(end = 16.dp)
                            .size(40.dp)
                            .background(Brush.linearGradient(listOf(NeonPurple, NeonBlue)), CircleShape)
                            .clickable { showProfileDialog = true }
                    ) { Text(text = myAvatar, fontSize = 20.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.primary) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    text = { Text("📢 Broadcast", style = MaterialTheme.typography.labelLarge, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("👥 Nearby", style = MaterialTheme.typography.labelLarge, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                            if (onlineCount > 0) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                    Text(onlineCount.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                    })
            }

            when (selectedTab) {
                0 -> BroadcastTab(myName = myName, myAvatar = myAvatar, myTag = myTag, btMessages = btMessages, onSend = onSendBroadcast)
                1 -> NearbyTab(contacts = contacts, unreadContacts = unreadContacts, onTap = { contact ->
                    // First tap → show profile dialog
                    // After that → go directly to chat
                    if (MetContactsStore.hasMet(ctx, contact.id)) {
                        onChatClick(contact)
                    } else {
                        selectedContact = contact
                    }
                })
            }
        }
    }

    if (showProfileDialog) {
        ProfileDetailDialog(myName = myName, myAvatar = myAvatar, myTag = myTag, myFullUsername = myFullUsername, onDismiss = { showProfileDialog = false })
    }

    selectedContact?.let { contact ->
        ContactProfileDialog(
            contact   = contact,
            onDismiss = { selectedContact = null },
            onMessage = {
                MetContactsStore.markAsMet(ctx, contact.id)
                selectedContact = null
                onChatClick(contact)
            }
        )
    }
}

// ─── Broadcast Tab ────────────────────────────────────────────────────────────
@Composable
fun BroadcastTab(
    myName     : String,
    myAvatar   : String,
    myTag      : String,
    btMessages : List<ChatMessage>,
    onSend     : (String) -> Unit
) {
    val scope     = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messageText       by remember { mutableStateOf("") }
    var broadcastMessages by remember { mutableStateOf<List<BroadcastMessage>>(emptyList()) }
    val seenIds           = remember { mutableSetOf<String>() }

    LaunchedEffect(btMessages) {
        val latest = btMessages.lastOrNull() ?: return@LaunchedEffect
        if (latest.id in seenIds) return@LaunchedEffect
        seenIds.add(latest.id)
        broadcastMessages = broadcastMessages + BroadcastMessage(
            id           = latest.id,
            senderName   = (latest.senderName.takeIf { it.isNotBlank() }) ?: "Unknown",
            senderAvatar = "🧑",
            text         = latest.text,
            isMine       = false
        )
        delay(100)
        listState.animateScrollToItem(0)
    }

    fun sendBroadcast() {
        if (messageText.isBlank()) return
        val text = messageText.trim()
        messageText = ""
        broadcastMessages = broadcastMessages + BroadcastMessage(
            senderName = "$myName#$myTag", senderAvatar = myAvatar, text = text, isMine = true
        )
        scope.launch {
            delay(100)
            listState.animateScrollToItem(0)
            onSend(text)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = "📡", fontSize = 14.sp)
                Text(text = "Messages reach everyone on the mesh — no scanning needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        if (broadcastMessages.isEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(32.dp)) {
                    Text(text = "📢", fontSize = 48.sp)
                    Text(text = "Broadcast Channel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Text(text = "Type a message below to reach\neveryone nearby on the mesh.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                state = listState, modifier = Modifier.weight(1f), reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(broadcastMessages.reversed(), key = { it.id }) { msg -> BroadcastBubble(message = msg) }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = messageText, onValueChange = { messageText = it },
                    placeholder = { Text("Send to everyone nearby...") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendBroadcast() }),
                    shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f), maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp)
                        .background(if (messageText.isNotBlank()) Brush.linearGradient(listOf(NeonPurple, NeonBlue)) else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)), CircleShape)
                        .clickable(enabled = messageText.isNotBlank()) { sendBroadcast() }
                ) { Text(text = "📢", fontSize = 18.sp) }
            }
        }
    }
}

// ─── Broadcast Bubble ─────────────────────────────────────────────────────────
@Composable
fun BroadcastBubble(message: BroadcastMessage) {
    Row(
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()
    ) {
        if (!message.isMine) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(end = 8.dp).size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                Text(text = message.senderAvatar, fontSize = 16.sp)
            }
        }
        val bubbleShape = RoundedCornerShape(18.dp, 18.dp, if (message.isMine) 4.dp else 18.dp, if (message.isMine) 18.dp else 4.dp)
        Column(horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start) {
            // Username shown above every received bubble
            if (!message.isMine) {
                Text(
                    text       = message.senderName,
                    style      = MaterialTheme.typography.labelSmall,
                    color      = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
            }
            Box(
                modifier = Modifier.widthIn(max = 280.dp).clip(bubbleShape)
                    .background(if (message.isMine) Brush.linearGradient(listOf(NeonPurple, NeonBlue)) else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))
                    .then(if (!message.isMine) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, bubbleShape) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(text = message.text, style = MaterialTheme.typography.bodyMedium, color = if (message.isMine) GlowWhite else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(text = message.time, style = MaterialTheme.typography.labelSmall, color = if (message.isMine) GlowWhite.copy(0.6f) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
                }
            }
        }
    }
}

// ─── Nearby Tab — shows online + offline devices ──────────────────────────────
@Composable
fun NearbyTab(contacts: List<ChatContact>, unreadContacts: Map<String, Int> = emptyMap(), onTap: (ChatContact) -> Unit) {
    val directContacts  = contacts.filter { it.reachType == ReachType.DIRECT }
    val meshContacts    = contacts.filter { it.reachType == ReachType.MESH }
    val offlineContacts = contacts.filter { it.reachType == ReachType.OFFLINE }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text("DEVICES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
            Text(
                text  = when {
                    directContacts.isEmpty() && meshContacts.isEmpty() -> "Scanning..."
                    meshContacts.isEmpty() -> "${directContacts.size} online"
                    else -> "${directContacts.size} online · ${meshContacts.size} via mesh"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (directContacts.isEmpty() && meshContacts.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else SoftCyan
            )
        }

        if (contacts.isEmpty()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(32.dp)) {
                    Text("👥", fontSize = 48.sp)
                    Text("Scanning for devices...", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                    Text("Other phones running Bluetooth Chat\nwill appear here automatically.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                // ── ONLINE — direct BLE range ──────────────────────────────
                if (directContacts.isNotEmpty()) {
                    item {
                        Text("ONLINE", style = MaterialTheme.typography.labelSmall, color = SoftCyan, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
                    }
                    itemsIndexed(directContacts) { i, c ->
                        ChatContactItem(contact = c, index = i, unreadCount = unreadContacts[c.username] ?: 0, onClick = { onTap(c) })
                    }
                }

                // ── MESH REACHABLE — via relay ─────────────────────────────
                if (meshContacts.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                        ) {
                            Text("MESH REACHABLE", style = MaterialTheme.typography.labelSmall, color = NeonBlue, letterSpacing = 1.sp)
                            Surface(shape = RoundedCornerShape(4.dp), color = NeonBlue.copy(0.15f)) {
                                Text("via relay", style = MaterialTheme.typography.labelSmall, color = NeonBlue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    itemsIndexed(meshContacts) { i, c ->
                        ChatContactItem(contact = c, index = i, unreadCount = unreadContacts[c.username] ?: 0, onClick = { onTap(c) })
                    }
                }

                // ── RECENTLY SEEN — offline ────────────────────────────────
                if (offlineContacts.isNotEmpty()) {
                    item {
                        Text("RECENTLY SEEN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
                    }
                    itemsIndexed(offlineContacts) { i, c ->
                        ChatContactItem(contact = c, index = i, unreadCount = unreadContacts[c.username] ?: 0, onClick = { onTap(c) })
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

// ─── Contact Profile Dialog ───────────────────────────────────────────────────
@Composable
fun ContactProfileDialog(contact: ChatContact, onDismiss: () -> Unit, onMessage: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onDismiss() }) {
                        Text("✕", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(Brush.linearGradient(listOf(NeonPurple.copy(0.2f), NeonBlue.copy(0.2f))), CircleShape).border(2.dp, Brush.linearGradient(listOf(NeonPurple, NeonBlue)), CircleShape)) {
                        Text(contact.avatar, fontSize = 38.sp)
                    }
                    Box(modifier = Modifier.size(20.dp).background(MaterialTheme.colorScheme.surface, CircleShape).padding(3.dp).background(if (contact.isOnline) SoftCyan else MaterialTheme.colorScheme.outline, CircleShape))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(contact.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(contact.username, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
                            Text("📶  Signal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                                listOf(5, 9, 13, 17).forEachIndexed { i, h ->
                                    Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(if (i < 3) NeonBlue else MaterialTheme.colorScheme.outline))
                                }
                            }
                        }
                    }
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
                            Text("🟢  Status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (contact.isOnline) "Online" else "Offline", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (contact.isOnline) SoftCyan else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
                            Text("🔒  Security", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("AES-256", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
                            Text("🌐  Network", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Mesh BLE", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Button(onClick = onMessage, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Text("🔒  Message Privately", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                Text("Tap to chat directly next time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

// ─── Profile Detail Dialog (my own profile) ───────────────────────────────────
@Composable
fun ProfileDetailDialog(myName: String, myAvatar: String, myTag: String, myFullUsername: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(24.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { onDismiss() }) {
                        Text("✕", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(Brush.linearGradient(listOf(NeonPurple, NeonBlue)), CircleShape)) {
                    Text(myAvatar, fontSize = 38.sp)
                }
                Text(myName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(myFullUsername, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ProfileInfoCard(icon = "🔖", label = "Device ID",  value = "#$myTag",  modifier = Modifier.weight(1f))
                    ProfileInfoCard(icon = "📡", label = "Status",     value = "Active",   modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ProfileInfoCard(icon = "🔒", label = "Encryption", value = "AES-256",  modifier = Modifier.weight(1f))
                    ProfileInfoCard(icon = "🌐", label = "Network",    value = "Mesh BLE", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── Profile Info Card ────────────────────────────────────────────────────────
@Composable
fun ProfileInfoCard(icon: String, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(icon, fontSize = 12.sp)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ─── Chat Contact Item ────────────────────────────────────────────────────────
@Composable
fun ChatContactItem(contact: ChatContact, index: Int = 0, unreadCount: Int = 0, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().alpha(when (contact.reachType) {
            ReachType.DIRECT  -> 1f
            ReachType.MESH    -> 0.85f
            ReachType.OFFLINE -> 0.55f
        }).clickable { onClick() },
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border   = BorderStroke(1.dp, if (contact.isOnline) NeonBlue.copy(0.3f) else MaterialTheme.colorScheme.outline)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(14.dp)) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).background(
                    if (contact.isOnline) Brush.linearGradient(listOf(NeonPurple.copy(0.2f), NeonBlue.copy(0.2f)))
                    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)), CircleShape)
                ) { Text(contact.avatar, fontSize = 22.sp) }

                if (unreadCount > 0) {
                    // Unread count badge — red circle with number
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(if (unreadCount > 9) 20.dp else 16.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape)
                    ) {
                        Text(
                            text  = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        )
                    }
                } else {
                    // Normal online/offline dot
                    Box(
                        Modifier.size(12.dp)
                            .background(MaterialTheme.colorScheme.background, CircleShape)
                            .padding(2.dp)
                            .background(
                                when (contact.reachType) {
                                    ReachType.DIRECT  -> SoftCyan                           // 🟢 cyan
                                    ReachType.MESH    -> NeonBlue                           // 🔵 blue
                                    ReachType.OFFLINE -> MaterialTheme.colorScheme.outline  // ⚫ grey
                                },
                                CircleShape
                            )
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(contact.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("#${contact.username.substringAfter("#")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary.copy(0.7f))
                }
                Text(
                    text     = when (contact.reachType) {
                        ReachType.DIRECT  -> contact.lastMessage
                        ReachType.MESH    -> contact.lastMessage  // "Reachable via Suman"
                        ReachType.OFFLINE -> "Out of range"
                    },
                    style    = MaterialTheme.typography.bodySmall,
                    color    = when (contact.reachType) {
                        ReachType.MESH -> NeonBlue.copy(0.8f)
                        else           -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
