package com.bitchat.android.ui.linkz

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * WeChat-style profile setup, shown once after permissions are granted
 * and the mesh is running. The user picks a nickname (prefilled with
 * the auto-generated one) and optionally a photo, then enters the app.
 * A blank nickname cannot be saved; Skip keeps the current name.
 * The photo is local only: the mesh carries nickname + fingerprint
 * but no photo, so other peers still see the initial-based avatar.
 */
@Composable
fun LinkzProfileSetupScreen(
    currentNickname: String,
    avatarBitmap: ImageBitmap?,
    onPickAvatar: () -> Unit,
    onDone: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(currentNickname) { mutableStateOf(currentNickname) }
    val trimmed = text.trim()
    val canDone = trimmed.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Set up your profile",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This is how people nearby will see you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        if (avatarBitmap != null) {
            Image(
                bitmap = avatarBitmap,
                contentDescription = "Your photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .clickable { onPickAvatar() }
            )
        } else {
            LinkzAvatar(
                name = trimmed.ifBlank { currentNickname },
                color = linkzAvatarColor(trimmed.ifBlank { currentNickname }),
                size = 96.dp,
                modifier = Modifier.clickable { onPickAvatar() }
            )
        }
        TextButton(onClick = onPickAvatar) {
            Text(text = if (avatarBitmap != null) "Change photo" else "Add photo")
        }
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text(text = "Nickname") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { onDone(trimmed) },
            enabled = canDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Done")
        }
        TextButton(onClick = onSkip) {
            Text(text = "Skip")
        }
    }
}
