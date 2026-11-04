package com.bitchat.android.ui.linkz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.ui.ChatViewModel

/**
 * WeChat-style long-press menu for a private-chat message. Until now
 * the DM list's long-press was a no-op stub; the timeline already had
 * Copy via the user sheet, but DMs had nothing.
 *
 * What is here, and why only this: Copy, Forward, and Delete are all
 * local or reuse the existing private-send path, so no protocol
 * change was needed. Quote, recall, and reactions are deliberately
 * absent: they need wire support the mesh does not have, and faking
 * them (a quote prefix in plain text, a local-only "recall") would
 * show other clients something different from what this phone shows.
 * Media messages get Delete only: forwarding a photo, voice note, or
 * file means re-sending its bytes, which is a follow-up, not this menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkzMessageMenuSheet(
    message: BitchatMessage,
    viewModel: ChatViewModel,
    currentPeerID: String?,
    onDismiss: () -> Unit
) {
    var showForwardPicker by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (!showForwardPicker) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = messageMenuLabel(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (message.type == BitchatMessageType.Message) {
                    LinkzMenuRow(
                        title = "Copy",
                        subtitle = "Copy the text to the clipboard",
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            onDismiss()
                        }
                    )
                    LinkzMenuRow(
                        title = "Forward",
                        subtitle = "Send this message to another chat",
                        onClick = { showForwardPicker = true }
                    )
                }
                LinkzMenuRow(
                    title = "Delete",
                    subtitle = "Only removes it from this phone",
                    onClick = {
                        viewModel.deleteMessageLocally(message.id)
                        onDismiss()
                    }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Forward to",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val targets = conversations.filter { summary ->
                    summary.conversationID != currentPeerID &&
                        summary.connectedPeerID != currentPeerID
                }
                if (targets.isEmpty()) {
                    Text(
                        text = "No other chats yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn {
                        items(targets, key = { it.conversationID }) { summary ->
                            val targetPeerID =
                                summary.connectedPeerID ?: summary.conversationID
                            val shownName = viewModel.getRemark(targetPeerID)
                                ?: summary.displayName
                            LinkzMenuRow(
                                title = shownName,
                                onClick = {
                                    viewModel.forwardMessage(targetPeerID, message.content)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun messageMenuLabel(message: BitchatMessage): String {
    return when (message.type) {
        BitchatMessageType.Audio -> "[Voice message]"
        BitchatMessageType.Image -> "[Photo]"
        BitchatMessageType.File -> message.content
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "[File]" }
        else -> message.content.take(120)
    }
}
