package com.bitchat.android.ui.linkz

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.ui.AboutSheet
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.LocationChannelsSheet
import com.bitchat.android.ui.MeshPeerListSheet
import com.bitchat.android.ui.PrivateChatSheet
import com.bitchat.android.ui.SecurityVerificationSheet
import com.bitchat.android.ui.VerificationSheet
import com.bitchat.android.ui.debug.DebugSettingsSheet

/**
 * The Linkz home shell: WeChat-style tabs over the unchanged Bitchat
 * engine.
 *
 * Navigation rule, kept deliberately small:
 * - The tab list is the home state. Opening anything that already has a
 *   Bitchat surface (a private chat sheet, a channel timeline, the mesh
 *   timeline) leaves the tab list and shows that surface.
 * - Closing that surface (sheet dismiss, channel switch back, back press)
 *   returns to the tab list, because the underlying ViewModel state that
 *   triggered the surface is cleared.
 * - Sheets that ChatScreen used to own (peer list, verification, about,
 *   debug) are rendered here too, so Discover and Me can open them
 *   without mounting the timeline.
 */
@Composable
fun LinkzShell(viewModel: ChatViewModel) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()
    val selectedPrivateChatPeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val showMeshPeerList by viewModel.showMeshPeerList.collectAsStateWithLifecycle()
    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val unreadChannelMessages by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val remarksTick by viewModel.remarksTick.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableStateOf(0) }
    val selectedTab = LinkzTab.entries[selectedTabIndex]
    var showMeshTimeline by remember { mutableStateOf(false) }
    var showDebugSheet by remember { mutableStateOf(false) }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showPanicDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val inTimeline = currentChannel != null ||
        selectedLocationChannel is ChannelID.Location ||
        showMeshTimeline

    if (inTimeline) {
        // The timeline surface is the existing ChatScreen, unchanged.
        // Back returns to the tab list instead of leaving the app.
        BackHandler {
            when {
                showMeshTimeline -> showMeshTimeline = false
                currentChannel != null -> viewModel.switchToChannel(null)
                selectedLocationChannel is ChannelID.Location ->
                    viewModel.selectLocationChannel(ChannelID.Mesh)
            }
        }
        ChatScreen(viewModel = viewModel)
        return
    }

    val totalUnread = conversations.sumOf { it.unreadCount } +
        unreadChannelMessages.values.sum()

    // Settings is a pushed page, not a tab: no bottom bar, back returns
    // to the tab list. Sheets below still render, so rows in Settings
    // can open the debug and about sheets.
    if (showSettings) {
        BackHandler { showSettings = false }
        LinkzSettingsScreen(
            onBack = { showSettings = false },
            onShowDebug = { showDebugSheet = true },
            onShowAbout = { viewModel.showAppInfo() },
            onShowPanicDialog = { showPanicDialog = true },
            onShowBlocked = {
                showSettings = false
                selectedTabIndex = LinkzTab.entries.indexOf(LinkzTab.CONTACTS)
            }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                LinkzBottomNav(
                    selected = selectedTab,
                    onSelect = { selectedTabIndex = LinkzTab.entries.indexOf(it) },
                    totalUnread = totalUnread
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    LinkzTab.CHATS -> LinkzChatsTab(
                        viewModel = viewModel,
                        conversations = conversations,
                        connectedPeers = connectedPeers,
                        peerNicknames = peerNicknames,
                        peerFingerprints = peerFingerprints,
                        joinedChannels = joinedChannels,
                        isConnected = isConnected,
                        remarksTick = remarksTick,
                        privateChats = privateChats,
                        totalUnread = totalUnread,
                        onOpenMeshTimeline = { showMeshTimeline = true }
                    )
                    LinkzTab.CONTACTS -> LinkzContactsTab(
                        viewModel = viewModel,
                        connectedPeers = connectedPeers,
                        peerNicknames = peerNicknames,
                        favoritePeers = favoritePeers,
                        peerFingerprints = peerFingerprints,
                        conversations = conversations,
                        remarksTick = remarksTick
                    )
                    LinkzTab.DISCOVER -> LinkzDiscoverTab(
                        viewModel = viewModel,
                        connectedPeersCount = connectedPeers.size,
                        joinedChannels = joinedChannels,
                        onOpenMeshTimeline = { showMeshTimeline = true },
                        onShowLocationChannels = { showLocationChannelsSheet = true }
                    )
                    LinkzTab.ME -> LinkzMeTab(
                        viewModel = viewModel,
                        nickname = nickname,
                        isConnected = isConnected,
                        nearbyCount = connectedPeers.size,
                        onShowSettings = { showSettings = true }
                    )
                }
            }
        }
    }

    // Sheets, rendered in list mode so Discover and Me can open them
    // without mounting the timeline (ChatScreen renders them in
    // timeline mode, so they are not duplicated here).
    if (showMeshPeerList) {
        MeshPeerListSheet(
            isPresented = true,
            viewModel = viewModel,
            onDismiss = viewModel::hideMeshPeerList,
            onShowVerification = viewModel::showVerificationSheet
        )
    }
    if (showVerificationSheet) {
        VerificationSheet(
            isPresented = true,
            onDismiss = viewModel::hideVerificationSheet,
            viewModel = viewModel
        )
    }
    if (showSecurityVerificationSheet) {
        SecurityVerificationSheet(
            isPresented = true,
            onDismiss = viewModel::hideSecurityVerificationSheet,
            viewModel = viewModel
        )
    }
    if (showAppInfo) {
        AboutSheet(
            isPresented = true,
            onDismiss = viewModel::hideAppInfo,
            onShowDebug = { showDebugSheet = true }
        )
    }
    if (showDebugSheet) {
        DebugSettingsSheet(
            isPresented = true,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }
    if (showLocationChannelsSheet) {
        LocationChannelsSheet(
            isPresented = true,
            onDismiss = { showLocationChannelsSheet = false },
            onLocationNotesClick = { showLocationChannelsSheet = false },
            viewModel = viewModel
        )
    }
    if (privateChatSheetPeer != null) {
        PrivateChatSheet(
            isPresented = true,
            peerID = privateChatSheetPeer!!,
            viewModel = viewModel,
            onDismiss = {
                viewModel.hidePrivateChatSheet()
                viewModel.endPrivateChat()
            }
        )
    }
    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text(text = "Clear all data?") },
            text = {
                Text(
                    text = "This erases all Linkz messages, contacts, and keys " +
                        "on this device. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPanicDialog = false
                        viewModel.panicClearAllData()
                    }
                ) {
                    Text(
                        text = "Clear all",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

@Composable
private fun LinkzSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(text = "Search") },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Search chats" }
    )
}

@Composable
private fun LinkzChatsTab(
    viewModel: ChatViewModel,
    conversations: List<com.bitchat.android.ui.ConversationSummary>,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerFingerprints: Map<String, String>,
    joinedChannels: Set<String>,
    isConnected: Boolean,
    remarksTick: Int,
    privateChats: Map<String, List<com.bitchat.android.model.BitchatMessage>>,
    totalUnread: Int,
    onOpenMeshTimeline: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showJoinChannelDialog by remember { mutableStateOf(false) }
    var joinChannelName by remember { mutableStateOf("") }
    var showNewChatPicker by remember { mutableStateOf(false) }
    var showAddContactsSheet by remember { mutableStateOf(false) }
    var showAddByIdDialog by remember { mutableStateOf(false) }
    var addByIdInput by remember { mutableStateOf("") }
    var addByIdNote by remember { mutableStateOf<String?>(null) }
    // Same people list as the Contacts tab: nearby peers plus past
    // conversations, deduped, blocked peers left out. Powers both the
    // New Chat picker and Add by ID matching.
    val people = remember(
        connectedPeers, peerNicknames, peerFingerprints,
        conversations, remarksTick
    ) {
        val fromPeers = connectedPeers
            .filter { !viewModel.isPeerBlocked(it) }
            .map { peerID ->
                val remark = viewModel.getRemark(peerID)
                LinkzContact(
                    id = peerID,
                    displayName = remark ?: peerNicknames[peerID] ?: peerID.take(12),
                    isConnected = true,
                    fingerprint = peerFingerprints[peerID],
                    remark = remark,
                    nickname = if (remark != null) peerNicknames[peerID] else null
                )
            }
        val fromConversations = conversations.map { summary ->
            val peerID = summary.connectedPeerID ?: summary.conversationID
            val remark = viewModel.getRemark(peerID)
            LinkzContact(
                id = summary.conversationID,
                displayName = remark ?: summary.displayName,
                isConnected = summary.isConnected,
                isBlocked = viewModel.isPeerBlocked(peerID),
                peerID = peerID,
                conversationID = summary.conversationID,
                remark = remark,
                nickname = if (remark != null) summary.displayName else null
            )
        }
        dedupeLinkzContacts(fromPeers + fromConversations)
            .filter { !it.isBlocked }
    }
    // remarksTick is a recomposition trigger: when a remark changes,
    // this tab re-reads viewModel.getRemark for every row below.
    @Suppress("UNUSED_VARIABLE")
    val remarkVersion = remarksTick
    // WeChat search covers message text, not just names and previews:
    // a conversation matches when any of its messages contains the
    // query. identityAliases already holds every key form the
    // conversation is stored under, so alias mismatches cannot hide
    // a match. Remark names match too, since rows show the remark.
    val filtered = conversations.filter { summary ->
        if (query.isBlank()) return@filter true
        val shownName = viewModel.getRemark(
            summary.connectedPeerID ?: summary.conversationID
        ) ?: summary.displayName
        if (shownName.contains(query, ignoreCase = true)) return@filter true
        if (summary.latestMessagePreview.contains(query, ignoreCase = true)) {
            return@filter true
        }
        privateChats.entries
            .filter { (key, _) -> key.lowercase() in summary.identityAliases }
            .flatMap { it.value }
            .any { it.content.contains(query, ignoreCase = true) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // WeChat-style header: plain title, no mesh-status subtitle (mesh
        // status still shows on Discover and Me). Search lives behind the
        // top-bar icon instead of a permanent field in the list.
        LinkzTopBar(
            // WeChat shows the unread count in the Chats title.
            title = if (totalUnread > 0) "Linkz ($totalUnread)" else "Linkz",
            onSearchClick = {
                showSearch = !showSearch
                if (!showSearch) query = ""
            },
            onAddClick = { showAddMenu = true }
        )
        // WeChat's + menu: dark popup, icon rows, New Chat first, then
        // Add Contacts, then Scan. Join channel stays last: it has no
        // WeChat equivalent, but dropping it would lose a Linkz feature.
        // Money is deliberately left out: Linkz has no payments.
        DropdownMenu(
            expanded = showAddMenu,
            onDismissRequest = { showAddMenu = false },
            containerColor = Color(0xFF4C4C4C)
        ) {
            DropdownMenuItem(
                text = { Text(text = "New Chat", color = Color.White) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.ChatBubble,
                        contentDescription = null,
                        tint = Color.White
                    )
                },
                onClick = {
                    showAddMenu = false
                    showNewChatPicker = true
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            DropdownMenuItem(
                text = { Text(text = "Add Contacts", color = Color.White) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.PersonAdd,
                        contentDescription = null,
                        tint = Color.White
                    )
                },
                onClick = {
                    showAddMenu = false
                    showAddContactsSheet = true
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            DropdownMenuItem(
                text = { Text(text = "Scan", color = Color.White) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = Color.White
                    )
                },
                onClick = {
                    showAddMenu = false
                    viewModel.showVerificationSheet()
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            DropdownMenuItem(
                text = { Text(text = "Join channel", color = Color.White) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Tag,
                        contentDescription = null,
                        tint = Color.White
                    )
                },
                onClick = {
                    showAddMenu = false
                    joinChannelName = ""
                    showJoinChannelDialog = true
                }
            )
        }
        if (!isConnected) {
            Text(
                text = "Mesh offline",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        if (showJoinChannelDialog) {
            AlertDialog(
                onDismissRequest = { showJoinChannelDialog = false },
                title = { Text(text = "Join channel") },
                text = {
                    TextField(
                        value = joinChannelName,
                        onValueChange = { joinChannelName = it },
                        placeholder = { Text(text = "Channel name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = joinChannelName.trim()
                            showJoinChannelDialog = false
                            if (name.isNotBlank() && viewModel.joinChannel(name)) {
                                viewModel.switchToChannel(name)
                            }
                        }
                    ) {
                        Text(text = "Join")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJoinChannelDialog = false }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
        if (showNewChatPicker) {
            AlertDialog(
                onDismissRequest = { showNewChatPicker = false },
                title = { Text(text = "New Chat") },
                text = {
                    if (people.isEmpty()) {
                        Text(
                            text = "No contacts yet. People nearby " +
                                "will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            items(people, key = { it.id.lowercase() }) { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showNewChatPicker = false
                                            viewModel.showPrivateChatSheet(
                                                contact.conversationID ?: contact.id
                                            )
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LinkzAvatar(
                                        name = contact.displayName,
                                        color = linkzAvatarColor(contact.id),
                                        size = 40.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = contact.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showNewChatPicker = false }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
        if (showAddContactsSheet) {
            AlertDialog(
                onDismissRequest = { showAddContactsSheet = false },
                title = { Text(text = "Add Contacts") },
                text = {
                    Column {
                        LinkzMenuRow(
                            title = "Scan QR",
                            onClick = {
                                showAddContactsSheet = false
                                viewModel.showVerificationSheet()
                            }
                        )
                        LinkzMenuRow(
                            title = "Nearby people",
                            onClick = {
                                showAddContactsSheet = false
                                viewModel.showMeshPeerList()
                            }
                        )
                        LinkzMenuRow(
                            title = "Add by ID",
                            onClick = {
                                showAddContactsSheet = false
                                addByIdInput = ""
                                addByIdNote = null
                                showAddByIdDialog = true
                            }
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showAddContactsSheet = false }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
        if (showAddByIdDialog) {
            AlertDialog(
                onDismissRequest = { showAddByIdDialog = false },
                title = { Text(text = "Add by ID") },
                text = {
                    Column {
                        Text(
                            text = "Paste a peer's Linkz ID or fingerprint.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = addByIdInput,
                            onValueChange = { addByIdInput = it },
                            placeholder = { Text(text = "Linkz ID or fingerprint") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (addByIdNote != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = addByIdNote!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val input = addByIdInput.trim()
                            val match = people.firstOrNull { contact ->
                                contact.fingerprint?.equals(input, ignoreCase = true) == true ||
                                    contact.id.equals(input, ignoreCase = true) ||
                                    contact.peerID.equals(input, ignoreCase = true)
                            }
                            if (match != null) {
                                showAddByIdDialog = false
                                viewModel.showPrivateChatSheet(
                                    match.conversationID ?: match.id
                                )
                            } else {
                                addByIdNote = "No nearby peer matches that ID. " +
                                    "Ask them to share their QR code."
                            }
                        }
                    ) {
                        Text(text = "Find")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddByIdDialog = false }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
        if (showSearch) {
            LinkzSearchField(query = query, onQueryChange = { query = it })
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                LinkzChatRow(
                    name = "#mesh",
                    preview = "Public mesh timeline",
                    timeLabel = "",
                    unreadCount = 0,
                    avatarColor = linkzAvatarColor("#mesh"),
                    onClick = onOpenMeshTimeline
                )
            }
            items(joinedChannels.sorted(), key = { "channel:$it" }) { channel ->
                LinkzChatRow(
                    name = "#$channel",
                    preview = "Channel",
                    timeLabel = "",
                    unreadCount = 0,
                    avatarColor = linkzAvatarColor(channel),
                    onClick = { viewModel.switchToChannel(channel) }
                )
            }
            items(filtered, key = { it.conversationID }) { summary ->
                LinkzSwipeableChatRow(
                    summary = summary,
                    viewModel = viewModel
                )
            }
            if (filtered.isEmpty() && joinedChannels.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 24.dp
                        )
                    ) {
                        Text(
                            text = "No chats yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "People you chat with on the mesh will appear here. " +
                                "Open the mesh timeline to find nearby people.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * A private-chat row with WeChat-style swipe actions. Swiping left
 * reveals buttons; it never deletes on its own. Pin, mute, mark
 * read/unread, and delete all call existing ViewModel actions, so
 * no engine change was needed. Channel and #mesh rows stay plain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkzSwipeableChatRow(
    summary: com.bitchat.android.ui.ConversationSummary,
    viewModel: ChatViewModel
) {
    val scope = rememberCoroutineScope()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { false }
    )
    val preview = summary.draft
        ?.takeIf { it.isNotBlank() }
        ?.let { "Draft: $it" }
        ?: summary.latestMessagePreview
    val shownName = viewModel.getRemark(
        summary.connectedPeerID ?: summary.conversationID
    ) ?: summary.displayName
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.setConversationRead(
                                summary.conversationID,
                                summary.unreadCount == 0
                            )
                            dismissState.reset()
                        }
                    }
                ) {
                    Text(text = if (summary.unreadCount > 0) "Read" else "Unread")
                }
                TextButton(
                    onClick = {
                        viewModel.toggleConversationPinned(summary.conversationID)
                        scope.launch { dismissState.reset() }
                    }
                ) {
                    Text(text = if (summary.isPinned) "Unpin" else "Pin")
                }
                TextButton(
                    onClick = {
                        viewModel.toggleConversationMuted(summary.conversationID)
                        scope.launch { dismissState.reset() }
                    }
                ) {
                    Text(text = if (summary.isMuted) "Unmute" else "Mute")
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.deletePrivateConversation(summary.conversationID)
                            dismissState.reset()
                        }
                    }
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    ) {
        LinkzChatRow(
            name = shownName,
            preview = preview,
            timeLabel = formatChatTime(summary.latestMessageAt),
            unreadCount = summary.unreadCount,
            avatarColor = linkzAvatarColor(summary.conversationID),
            isMuted = summary.isMuted,
            onClick = { viewModel.showPrivateChatSheet(summary.conversationID) }
        )
    }
}

@Composable
private fun LinkzContactsTab(
    viewModel: ChatViewModel,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    favoritePeers: Set<String>,
    peerFingerprints: Map<String, String>,
    conversations: List<com.bitchat.android.ui.ConversationSummary>,
    remarksTick: Int
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var showAddByIdDialog by remember { mutableStateOf(false) }
    var addByIdInput by remember { mutableStateOf("") }
    var addByIdNote by remember { mutableStateOf<String?>(null) }
    // Block state is not a StateFlow, so bump this after a block/unblock
    // to recompute the list below.
    var blockTick by remember { mutableStateOf(0) }
    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val baseContacts = remember(
        connectedPeers, peerNicknames, favoritePeers, peerFingerprints,
        conversations, verifiedFingerprints, blockTick, remarksTick
    ) {
        val fromPeers = connectedPeers.map { peerID ->
            val fingerprint = peerFingerprints[peerID]
            val nickname = peerNicknames[peerID] ?: peerID.take(12)
            val remark = viewModel.getRemark(peerID)
            LinkzContact(
                id = peerID,
                displayName = remark ?: nickname,
                isFavorite = fingerprint != null && favoritePeers.contains(fingerprint),
                isConnected = true,
                fingerprint = fingerprint,
                isVerified = fingerprint != null && verifiedFingerprints.contains(fingerprint),
                isBlocked = viewModel.isPeerBlocked(peerID),
                remark = remark,
                nickname = if (remark != null) nickname else null
            )
        }
        val fromConversations = conversations.map { summary ->
            val peerID = summary.connectedPeerID ?: summary.conversationID
            val fingerprint = summary.connectedPeerID?.let { peerFingerprints[it] }
                ?: peerFingerprints[summary.conversationID]
            val remark = viewModel.getRemark(peerID)
            LinkzContact(
                id = summary.conversationID,
                displayName = remark ?: summary.displayName,
                isFavorite = fingerprint != null && favoritePeers.contains(fingerprint) ||
                    favoritePeers.contains(summary.conversationID),
                isConnected = summary.isConnected,
                fingerprint = fingerprint,
                isVerified = fingerprint != null && verifiedFingerprints.contains(fingerprint),
                isBlocked = viewModel.isPeerBlocked(peerID),
                isMuted = summary.isMuted,
                peerID = peerID,
                conversationID = summary.conversationID,
                remark = remark,
                nickname = if (remark != null) summary.displayName else null
            )
        }
        dedupeLinkzContacts(fromPeers + fromConversations)
    }
    val contacts = baseContacts.filter {
        query.isBlank() || it.displayName.contains(query, ignoreCase = true)
    }
    val favorites = contacts.filter { it.isFavorite && !it.isBlocked }
    val others = contacts.filter { !it.isFavorite && !it.isBlocked }
    val blocked = contacts.filter { it.isBlocked }
    Column(modifier = Modifier.fillMaxSize()) {
        LinkzTopBar(
            title = "Contacts",
            subtitle = "${contacts.size} contacts",
            onSearchClick = {
                showSearch = !showSearch
                if (!showSearch) query = ""
            },
            onAddClick = { showAddMenu = true }
        )
        DropdownMenu(
            expanded = showAddMenu,
            onDismissRequest = { showAddMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(text = "Scan QR") },
                onClick = {
                    showAddMenu = false
                    viewModel.showVerificationSheet()
                }
            )
            DropdownMenuItem(
                text = { Text(text = "Nearby people") },
                onClick = {
                    showAddMenu = false
                    viewModel.showMeshPeerList()
                }
            )
            DropdownMenuItem(
                text = { Text(text = "Add by ID") },
                onClick = {
                    showAddMenu = false
                    addByIdInput = ""
                    addByIdNote = null
                    showAddByIdDialog = true
                }
            )
        }
        if (showAddByIdDialog) {
            AlertDialog(
                onDismissRequest = { showAddByIdDialog = false },
                title = { Text(text = "Add by ID") },
                text = {
                    Column {
                        Text(
                            text = "Paste a peer's Linkz ID or fingerprint. " +
                                "IDs only match people in range.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = addByIdInput,
                            onValueChange = { addByIdInput = it },
                            placeholder = { Text(text = "Linkz ID or fingerprint") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (addByIdNote != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = addByIdNote!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val input = addByIdInput.trim()
                            val match = baseContacts.firstOrNull { contact ->
                                contact.fingerprint?.equals(input, ignoreCase = true) == true ||
                                    contact.id.equals(input, ignoreCase = true) ||
                                    contact.peerID.equals(input, ignoreCase = true)
                            }
                            if (match != null) {
                                showAddByIdDialog = false
                                selectedContactId = match.id
                            } else {
                                addByIdNote = "No nearby peer matches that ID. " +
                                    "Ask them to share their QR code."
                            }
                        }
                    ) {
                        Text(text = "Find")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddByIdDialog = false }) {
                        Text(text = "Cancel")
                    }
                }
            )
        }
        if (showSearch) {
            LinkzSearchField(query = query, onQueryChange = { query = it })
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (favorites.isNotEmpty()) {
                item { LinkzSectionHeader(text = "Favorites") }
                items(favorites, key = { it.id.lowercase() }) { contact ->
                    LinkzContactRow(contact) { selectedContactId = contact.id }
                }
            }
            if (others.isNotEmpty()) {
                item { LinkzSectionHeader(text = "Contacts") }
                items(others, key = { it.id.lowercase() }) { contact ->
                    LinkzContactRow(contact) { selectedContactId = contact.id }
                }
            }
            if (blocked.isNotEmpty()) {
                item { LinkzSectionHeader(text = "Blocked") }
                items(blocked, key = { it.id.lowercase() }) { contact ->
                    LinkzContactRow(contact) { selectedContactId = contact.id }
                }
            }
            if (contacts.isEmpty()) {
                item {
                    Text(
                        text = "No contacts yet. People you chat with and " +
                            "nearby peers will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
    // Re-derive the sheet's contact from the list so toggles (favorite,
    // mute, block) update the sheet instead of showing stale state.
    val selectedContact = selectedContactId?.let { id ->
        baseContacts.firstOrNull { it.id == id }
    }
    if (selectedContact != null) {
        LinkzContactSheet(
            contact = selectedContact,
            onDismiss = { selectedContactId = null },
            onSendMessage = {
                selectedContactId = null
                viewModel.showPrivateChatSheet(
                    selectedContact.conversationID ?: selectedContact.id
                )
            },
            onVerify = {
                selectedContactId = null
                viewModel.showVerificationSheet()
            },
            onToggleFavorite = { viewModel.toggleFavorite(selectedContact.peerID) },
            onToggleMute = selectedContact.conversationID?.let { conversationID ->
                { viewModel.toggleConversationMuted(conversationID) }
            },
            onToggleBlock = {
                if (selectedContact.isBlocked) {
                    viewModel.unblockPeer(selectedContact.peerID)
                } else {
                    viewModel.blockPeer(selectedContact.peerID)
                }
                blockTick++
            },
            remark = selectedContact.remark,
            onSetRemark = { viewModel.setRemark(selectedContact.peerID, it) }
        )
    }
}

@Composable
private fun LinkzContactRow(contact: LinkzContact, onClick: () -> Unit) {
    val preview = when {
        contact.isBlocked -> "Blocked"
        contact.isFavorite -> "★ Favorite"
        contact.isConnected -> "Nearby"
        else -> "Past chat"
    }
    LinkzChatRow(
        name = contact.displayName,
        preview = preview,
        timeLabel = "",
        unreadCount = 0,
        avatarColor = linkzAvatarColor(contact.id),
        isMuted = contact.isMuted,
        onClick = onClick
    )
}

@Composable
private fun LinkzDiscoverTab(
    viewModel: ChatViewModel,
    connectedPeersCount: Int,
    joinedChannels: Set<String>,
    onOpenMeshTimeline: () -> Unit,
    onShowLocationChannels: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LinkzTopBar(title = "Discover")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(8.dp))
                LinkzSectionHeader(text = "Nearby")
                LinkzMenuRow(
                    title = "Nearby people",
                    subtitle = "$connectedPeersCount nearby",
                    onClick = { viewModel.showMeshPeerList() }
                )
                LinkzMenuRow(
                    title = "Location channels",
                    subtitle = "Channels for places around you",
                    onClick = onShowLocationChannels
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinkzSectionHeader(text = "Channels")
                if (joinedChannels.isEmpty()) {
                    LinkzMenuRow(
                        title = "Channels",
                        subtitle = "Join a channel from the mesh timeline",
                        onClick = onOpenMeshTimeline
                    )
                } else {
                    for (channel in joinedChannels.sorted()) {
                        LinkzMenuRow(
                            title = "#$channel",
                            onClick = { viewModel.switchToChannel(channel) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinkzSectionHeader(text = "Scan")
                LinkzMenuRow(
                    title = "Scan QR code",
                    subtitle = "Verify a contact by QR code",
                    onClick = { viewModel.showVerificationSheet() }
                )
            }
        }
    }
}

@Composable
private fun LinkzMeTab(
    viewModel: ChatViewModel,
    nickname: String,
    isConnected: Boolean,
    nearbyCount: Int,
    onShowSettings: () -> Unit
) {
    val displayName = nickname.ifBlank { "Set a nickname" }
    val fingerprint = try {
        viewModel.getMyFingerprint()
    } catch (_: Exception) {
        ""
    }
    var showNicknameSheet by remember { mutableStateOf(false) }
    // The photo is local only, like the onboarding photo: changing it
    // here updates this phone's Me header, not what peers see.
    var ownAvatar by remember { mutableStateOf(viewModel.loadAvatarBitmap()) }
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && viewModel.setAvatarFromUri(uri)) {
            ownAvatar = viewModel.loadAvatarBitmap()
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LinkzTopBar(title = "Me")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .clickable(role = Role.Button) {
                            pickPhoto.launch("image/*")
                        }
                        .semantics { contentDescription = "Change photo" }
                ) {
                    if (ownAvatar != null) {
                        Image(
                            bitmap = ownAvatar!!,
                            contentDescription = "Your photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        LinkzAvatar(
                            name = displayName,
                            color = linkzAvatarColor(displayName),
                            size = 64.dp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) { showNicknameSheet = true }
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (fingerprint.isNotBlank()) {
                        Text(
                            text = "Linkz ID: ${shortFingerprint(fingerprint)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "Mesh")
        LinkzMenuRow(
            title = "Mesh status",
            subtitle = if (isConnected) {
                "Connected · $nearbyCount nearby"
            } else {
                "Not connected"
            },
            onClick = { viewModel.showMeshPeerList() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "App")
        LinkzMenuRow(
            title = "Settings",
            subtitle = "Appearance, language, notifications, blocked, debug",
            onClick = onShowSettings
        )
    }
    if (showNicknameSheet) {
        LinkzNicknameSheet(
            currentNickname = nickname,
            onDismiss = { showNicknameSheet = false },
            onSave = { newNickname ->
                viewModel.setNickname(newNickname)
                showNicknameSheet = false
            }
        )
    }
}
