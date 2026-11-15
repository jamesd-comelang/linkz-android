package com.bitchat.android.ui.linkz

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.nostr.FriendRequest
import com.bitchat.android.nostr.FriendRequestDirection
import com.bitchat.android.nostr.FriendRequestProtocol
import com.bitchat.android.nostr.FriendRequestStatus
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.ui.ChatViewModel

/**
 * New Friends (新的朋友): the friend-request inbox, WeChat-style. Requests
 * land here, not in the chat list, and there is no chatting until the
 * request is accepted. Pending incoming requests first, then pending
 * outgoing, then the rest newest first. Accepted rows open the DM.
 */
@Composable
fun LinkzNewFriendsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val requests by viewModel.friendRequests.collectAsStateWithLifecycle()
    var selectedRequest by remember { mutableStateOf<FriendRequest?>(null) }
    val sorted = remember(requests) {
        requests.sortedWith(
            compareByDescending<FriendRequest> {
                it.status == FriendRequestStatus.PENDING &&
                    it.direction == FriendRequestDirection.INCOMING
            }.thenByDescending {
                it.status == FriendRequestStatus.PENDING
            }.thenByDescending { it.createdAtMs }
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LinkzTopBar(title = "New Friends", onBackClick = onBack)
        if (sorted.isEmpty()) {
            Text(
                text = "No new friends yet. Share your invite code to let " +
                    "someone add you, or paste theirs to add them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sorted, key = { it.requestId }) { request ->
                    LinkzFriendRequestRow(
                        request = request,
                        onAccept = { viewModel.acceptFriendRequest(request.requestId) },
                        onDecline = { viewModel.declineFriendRequest(request.requestId) },
                        onOpenChat = { viewModel.openFriendChat(request) },
                        onShowDetail = { selectedRequest = request }
                    )
                }
            }
        }
    }
    selectedRequest?.let { request ->
        LinkzFriendRequestDetailDialog(
            request = request,
            onDismiss = { selectedRequest = null }
        )
    }
}

@Composable
private fun LinkzFriendRequestRow(
    request: FriendRequest,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onOpenChat: () -> Unit,
    onShowDetail: () -> Unit
) {
    val expired = request.isExpired()
    val name = requestDisplayName(request)
    val subtitle = when {
        request.status == FriendRequestStatus.ACCEPTED -> "Accepted"
        request.status == FriendRequestStatus.DECLINED -> "Declined"
        expired -> "Expired"
        request.direction == FriendRequestDirection.INCOMING ->
            request.greeting.ifBlank { "Wants to add you" }
        else -> "Waiting for accept"
    }
    val canChat = request.status == FriendRequestStatus.ACCEPTED
    val showActions = request.status == FriendRequestStatus.PENDING &&
        !expired && request.direction == FriendRequestDirection.INCOMING
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (canChat) onOpenChat() else onShowDetail() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LinkzAvatar(
                name = name,
                color = linkzAvatarColor(request.remotePubkeyHex),
                size = 48.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showActions) {
                TextButton(onClick = onDecline) {
                    Text(text = "Decline")
                }
                Button(
                    onClick = onAccept,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(text = "Accept")
                }
            }
        }
    }
}

private fun shortNpub(npub: String): String =
    if (npub.length > 16) "${npub.take(8)}...${npub.takeLast(8)}" else npub

private fun requestDisplayName(request: FriendRequest): String =
    request.remoteNickname.takeIf { it.isNotBlank() }
        ?: ContactIdentityResolver.npubFromHex(request.remotePubkeyHex)
            ?.let { shortNpub(it) }
        ?: "Unknown"

private fun formatRequestDate(ms: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

/**
 * Detail for a request that is not yet an open chat: who, what was said,
 * when it was sent and until when it stays valid. Accepted rows open the
 * DM instead, so this only ever shows for pending, declined or expired
 * requests. WeChat shows the same sent-request detail on tap.
 */
@Composable
private fun LinkzFriendRequestDetailDialog(
    request: FriendRequest,
    onDismiss: () -> Unit
) {
    val expired = request.isExpired()
    val status = when {
        request.status == FriendRequestStatus.ACCEPTED -> "Accepted"
        request.status == FriendRequestStatus.DECLINED -> "Declined"
        expired -> "Expired"
        else -> "Waiting for accept"
    }
    val direction = if (request.direction == FriendRequestDirection.INCOMING) {
        "This person wants to add you."
    } else {
        "You sent this request."
    }
    val lines = buildList {
        add(direction)
        if (request.greeting.isNotBlank()) add("Message: ${request.greeting}")
        add("Sent: ${formatRequestDate(request.createdAtMs)}")
        if (request.status == FriendRequestStatus.PENDING && !expired) {
            add(
                "Valid until: " +
                    formatRequestDate(
                        request.createdAtMs + FriendRequestProtocol.REQUEST_VALIDITY_MS
                    )
            )
        }
        add("Status: $status")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = requestDisplayName(request)) },
        text = {
            Column {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = "Close") }
        }
    )
}
