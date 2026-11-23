package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attachment
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.ui.ComposerActionSurface
import com.bitchat.android.ui.ComposerIconSize

@Composable
fun FilePickerButton(
    modifier: Modifier = Modifier,
    onFileReady: (String) -> Unit
) {
    val context = LocalContext.current

    // Use SAF - supports all file types
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist temporary read permission so we can copy
            try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val path = FileUtils.copyFileForSending(context, uri)
            if (!path.isNullOrBlank()) onFileReady(path)
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Shares the composer's button treatment so file, camera, microphone and send read as one
    // set — the same disc the camera button uses in the attach panel.
    ComposerActionSurface(
        isActive = false,
        isPressed = isPressed,
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                // Allow any MIME type; user asked to choose between image or file at higher level UI
                filePicker.launch(arrayOf("*/*"))
            }
        )
    ) { tint ->
        Icon(
            imageVector = Icons.Filled.Attachment,
            contentDescription = stringResource(R.string.cd_pick_file),
            tint = tint,
            modifier = Modifier.size(ComposerIconSize).rotate(90f)
        )
    }
}
