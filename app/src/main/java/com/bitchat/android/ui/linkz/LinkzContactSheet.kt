package com.bitchat.android.ui.linkz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * WeChat-style contact profile sheet, opened from a Contacts row.
 * Message and Verify reuse the existing sheets; favorite, mute, and
 * block call the existing ViewModel actions. A blocked contact shows
 * Unblock instead of Send message, since a blocked peer cannot be
 * messaged. The sheet stays open across toggles so the new state is
 * visible; the caller re-derives [contact] from the Contacts list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkzContactSheet(
    contact: LinkzContact,
    onDismiss: () -> Unit,
    onSendMessage: () -> Unit,
    onVerify: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleMute: (() -> Unit)?,
    onToggleBlock: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinkzAvatar(
                    name = contact.displayName,
                    color = linkzAvatarColor(contact.id)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = contactStatusLine(contact),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (contact.fingerprint != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Fingerprint ${shortFingerprint(contact.fingerprint)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!contact.isBlocked) {
                LinkzMenuRow(
                    title = "Send message",
                    subtitle = "Open a private chat",
                    onClick = onSendMessage
                )
            }
            if (!contact.isVerified) {
                LinkzMenuRow(
                    title = "Verify contact",
                    subtitle = "Scan their QR code",
                    onClick = onVerify
                )
            }
            LinkzMenuRow(
                title = if (contact.isFavorite) "Remove from favorites" else "Add to favorites",
                onClick = onToggleFavorite
            )
            if (onToggleMute != null) {
                LinkzMenuRow(
                    title = if (contact.isMuted) "Unmute conversation" else "Mute conversation",
                    onClick = onToggleMute
                )
            }
            LinkzMenuRow(
                title = if (contact.isBlocked) "Unblock contact" else "Block contact",
                subtitle = if (contact.isBlocked) null else "You will not receive messages from this peer",
                onClick = onToggleBlock
            )
        }
    }
}

private fun contactStatusLine(contact: LinkzContact): String {
    val parts = mutableListOf<String>()
    if (contact.isBlocked) {
        parts += "Blocked"
    } else if (contact.isConnected) {
        parts += "Nearby"
    } else {
        parts += "Past chat"
    }
    if (contact.isVerified) parts += "Verified"
    if (contact.isFavorite) parts += "Favorite"
    if (contact.isMuted) parts += "Muted"
    return parts.joinToString(" · ")
}
