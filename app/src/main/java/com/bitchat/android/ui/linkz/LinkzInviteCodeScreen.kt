package com.bitchat.android.ui.linkz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.nostr.InviteCodeManager
import com.bitchat.android.ui.ChatViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

/**
 * My Invite Code (一次性邀请码): the one-time npub that can receive exactly
 * one friend request — nothing else. The code stops working after one
 * request or 3 days, and regenerating invalidates the previous code
 * (WeChat's "reset my QR code" semantics).
 */
@Composable
fun LinkzInviteCodeScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val inviteTick by viewModel.inviteTick.collectAsStateWithLifecycle()
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // First open generates the code when there is none.
    LaunchedEffect(Unit) {
        if (viewModel.getInviteStatus() is InviteCodeManager.InviteStatus.None) {
            viewModel.getOrCreateInviteCode()
        }
    }
    if (copied) {
        LaunchedEffect(copied) {
            delay(2000)
            copied = false
        }
    }

    // Recomputed when the invite changes (created / regenerated). A request
    // arriving while this screen is open consumes the code in the
    // background; the status refreshes the next time the screen opens.
    val status = remember(inviteTick) { viewModel.getInviteStatus() }
    val valid = status as? InviteCodeManager.InviteStatus.Valid

    Column(modifier = Modifier.fillMaxSize()) {
        LinkzTopBar(title = "My Invite Code", onBackClick = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (valid != null) {
                LinkzQRCodeImage(data = valid.identity.npub, size = 240.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = valid.identity.getShortNpub(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Linkz invite code", valid.identity.npub)
                    )
                    copied = true
                }) {
                    Text(text = if (copied) "Copied" else "Copy code")
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Valid until ${formatInviteDate(valid.expiresAtMs)} · one request only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = when (status) {
                        is InviteCodeManager.InviteStatus.Used ->
                            "Already used — this code accepted one request."
                        is InviteCodeManager.InviteStatus.Expired ->
                            "This code expired after 3 days."
                        else -> "No code yet."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.regenerateInviteCode() }) {
                    Text(text = "Generate new code")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Anyone with this code can send you one friend request — nothing else. " +
                    "The code stops working after one request or 3 days. " +
                    "Share it as a QR code or copy the text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (valid != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = { viewModel.regenerateInviteCode() }) {
                    Text(text = "Generate new code (invalidates this one)")
                }
            }
        }
    }
}

/**
 * Add by Invite Code: paste someone's one-time npub and send them a friend
 * request with a greeting. The dialog closes and opens New Friends on
 * success, so the pending request is visible immediately.
 */
@Composable
fun LinkzSendInviteRequestDialog(
    viewModel: ChatViewModel,
    prefilledNpub: String?,
    onDismiss: () -> Unit,
    onSent: () -> Unit
) {
    var inviteInput by remember { mutableStateOf(prefilledNpub ?: "") }
    var greeting by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add by Invite Code") },
        text = {
            Column {
                Text(
                    text = "Paste the invite code (npub) someone shared with you. " +
                        "It can only send them a friend request. Use the code from " +
                        "their My Invite Code screen, not their QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = inviteInput,
                    onValueChange = { inviteInput = it; error = null },
                    placeholder = { Text(text = "npub1…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = greeting,
                    onValueChange = { greeting = it },
                    placeholder = { Text(text = "Greeting (optional)") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (viewModel.sendFriendRequest(inviteInput, greeting)) {
                    ChatViewModel.SendFriendRequestResult.SENT -> {
                        onDismiss()
                        onSent()
                    }
                    ChatViewModel.SendFriendRequestResult.INVALID_CODE ->
                        error = "That doesn't look like an invite code. Paste the full npub."
                    ChatViewModel.SendFriendRequestResult.SELF_CODE ->
                        error = "That's your own invite code."
                    ChatViewModel.SendFriendRequestResult.NO_IDENTITY ->
                        error = "Couldn't load your identity. Try again."
                }
            }) {
                Text(text = "Send request")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@Composable
private fun LinkzQRCodeImage(data: String, size: Dp) {
    val sizePx = with(LocalDensity.current) { size.toPx().toInt() }
    val bitmap = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Invite code QR code",
            modifier = Modifier.size(size)
        )
    }
}

private fun generateQrBitmap(data: String, sizePx: Int): Bitmap? {
    if (data.isBlank() || sizePx <= 0) return null
    return try {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        bitmapFromMatrix(matrix)
    } catch (_: Exception) {
        null
    }
}

private fun bitmapFromMatrix(matrix: BitMatrix): Bitmap {
    val width = matrix.width
    val height = matrix.height
    val bitmap = createBitmap(width, height)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap[x, y] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return bitmap
}

private fun formatInviteDate(ms: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(ms))
