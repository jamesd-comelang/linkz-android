package com.bitchat.android.ui.linkz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    var selectedTabIndex by remember { mutableStateOf(0) }
    val selectedTab = LinkzTab.entries[selectedTabIndex]
    var showMeshTimeline by remember { mutableStateOf(false) }
    var showDebugSheet by remember { mutableStateOf(false) }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showPanicDialog by remember { mutableStateOf(false) }

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
                    joinedChannels = joinedChannels,
                    onOpenMeshTimeline = { showMeshTimeline = true }
                )
                LinkzTab.CONTACTS -> LinkzContactsTab(
                    viewModel = viewModel,
                    connectedPeers = connectedPeers,
                    peerNicknames = peerNicknames,
                    favoritePeers = favoritePeers,
                    peerFingerprints = peerFingerprints,
                    conversations = conversations
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
                    onShowDebug = { showDebugSheet = true },
                    onShowPanicDialog = { showPanicDialog = true }
                )
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
    )
}

@Composable
private fun LinkzChatsTab(
    viewModel: ChatViewModel,
    conversations: List<com.bitchat.android.ui.ConversationSummary>,
    joinedChannels: Set<String>,
    onOpenMeshTimeline: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    val filtered = conversations.filter {
        query.isBlank() ||
            it.displayName.contains(query, ignoreCase = true) ||
            it.latestMessagePreview.contains(query, ignoreCase = true)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // WeChat-style header: plain title, no mesh-status subtitle (mesh
        // status still shows on Discover and Me). Search lives behind the
        // top-bar icon instead of a permanent field in the list.
        LinkzTopBar(
            title = "Linkz",
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
                val preview = summary.draft
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "Draft: $it" }
                    ?: summary.latestMessagePreview
                LinkzChatRow(
                    name = summary.displayName,
                    preview = preview,
                    timeLabel = formatChatTime(summary.latestMessageAt),
                    unreadCount = summary.unreadCount,
                    avatarColor = linkzAvatarColor(summary.conversationID),
                    isMuted = summary.isMuted,
                    onClick = { viewModel.showPrivateChatSheet(summary.conversationID) }
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

@Composable
private fun LinkzContactsTab(
    viewModel: ChatViewModel,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    favoritePeers: Set<String>,
    peerFingerprints: Map<String, String>,
    conversations: List<com.bitchat.android.ui.ConversationSummary>
) {
    var query by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    // Block state is not a StateFlow, so bump this after a block/unblock
    // to recompute the list below.
    var blockTick by remember { mutableStateOf(0) }
    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val baseContacts = remember(
        connectedPeers, peerNicknames, favoritePeers, peerFingerprints,
        conversations, verifiedFingerprints, blockTick
    ) {
        val fromPeers = connectedPeers.map { peerID ->
            val fingerprint = peerFingerprints[peerID]
            LinkzContact(
                id = peerID,
                displayName = peerNicknames[peerID] ?: peerID.take(12),
                isFavorite = fingerprint != null && favoritePeers.contains(fingerprint),
                isConnected = true,
                fingerprint = fingerprint,
                isVerified = fingerprint != null && verifiedFingerprints.contains(fingerprint),
                isBlocked = viewModel.isPeerBlocked(peerID)
            )
        }
        val fromConversations = conversations.map { summary ->
            val peerID = summary.connectedPeerID ?: summary.conversationID
            val fingerprint = summary.connectedPeerID?.let { peerFingerprints[it] }
                ?: peerFingerprints[summary.conversationID]
            LinkzContact(
                id = summary.conversationID,
                displayName = summary.displayName,
                isFavorite = fingerprint != null && favoritePeers.contains(fingerprint) ||
                    favoritePeers.contains(summary.conversationID),
                isConnected = summary.isConnected,
                fingerprint = fingerprint,
                isVerified = fingerprint != null && verifiedFingerprints.contains(fingerprint),
                isBlocked = viewModel.isPeerBlocked(peerID),
                isMuted = summary.isMuted,
                peerID = peerID,
                conversationID = summary.conversationID
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
            }
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
    onShowDebug: () -> Unit,
    onShowPanicDialog: () -> Unit
) {
    val displayName = nickname.ifBlank { "Set a nickname" }
    val fingerprint = try {
        viewModel.getMyFingerprint()
    } catch (_: Exception) {
        ""
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
                LinkzAvatar(
                    name = displayName,
                    color = linkzAvatarColor(displayName),
                    size = 64.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
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
            title = "About Linkz",
            subtitle = "App info and QR code",
            onClick = { viewModel.showAppInfo() }
        )
        LinkzMenuRow(
            title = "Debug settings",
            subtitle = "Mesh and transport options",
            onClick = onShowDebug
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "Danger")
        LinkzMenuRow(
            title = "Clear all data",
            subtitle = "Erases all messages and keys on this device",
            onClick = onShowPanicDialog
        )
    }
}
