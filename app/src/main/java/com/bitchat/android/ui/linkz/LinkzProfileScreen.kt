package com.bitchat.android.ui.linkz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.ChatViewModel

/**
 * Own Profile page, copied from WeChat's Profile screen layout: one
 * section, title on the left, value on the right, chevron at the far
 * right. Only rows that mean something in Linkz are kept:
 *
 * - Profile Photo, Name, Gender, Region, ID, My QR Code, My Invite Code,
 *   What's Up.
 * - Mobile Number shows "Not Linked": Linkz has no accounts or
 *   phone numbers, so tapping it explains that instead of editing.
 * - Gender, Region, and What's Up are local only, like the photo:
 *   they live in this phone's prefs and are never sent to peers.
 *
 * Left out, because Linkz has no equivalent: Pay and Services,
 * Stores and Cards, Sticker Gallery, Call Ringtone, My Address, My
 * Fapiao Titles, WeBeans, Tickle (needs a peer interaction), and My
 * Posts (needs Moments, a later phase). Icons are Material icons, not
 * WeChat artwork.
 */
@Composable
fun LinkzProfileScreen(
    viewModel: ChatViewModel,
    nickname: String,
    onBack: () -> Unit,
    onShowInviteCode: () -> Unit = {}
) {
    val context = LocalContext.current
    val profileTick by viewModel.profileTick.collectAsStateWithLifecycle()
    // profileTick is a recomposition trigger: when a profile field
    // changes, this screen re-reads the DataManager getters below.
    @Suppress("UNUSED_VARIABLE")
    val profileVersion = profileTick
    val gender = viewModel.getProfileGender()
    val region = viewModel.getProfileRegion()
    val status = viewModel.getProfileStatus()
    val fingerprint = try {
        viewModel.getMyFingerprint()
    } catch (_: Exception) {
        ""
    }
    val displayName = nickname.ifBlank { "Set a nickname" }

    // The photo is local only, like the onboarding photo: changing it
    // here updates this phone's profile, not what peers see.
    var ownAvatar by remember { mutableStateOf(viewModel.loadAvatarBitmap()) }
    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && viewModel.setAvatarFromUri(uri)) {
            ownAvatar = viewModel.loadAvatarBitmap()
        }
    }

    var showNicknameSheet by remember { mutableStateOf(false) }
    var showGenderDialog by remember { mutableStateOf(false) }
    var showRegionSheet by remember { mutableStateOf(false) }
    var showStatusSheet by remember { mutableStateOf(false) }
    var showPhoneDialog by remember { mutableStateOf(false) }
    var showIdDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        LinkzTopBar(title = "Profile", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinkzProfileRow(
                    title = "Profile Photo",
                    onClick = { pickPhoto.launch("image/*") },
                    trailing = {
                        if (ownAvatar != null) {
                            Image(
                                bitmap = ownAvatar!!,
                                contentDescription = "Your photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            LinkzAvatar(
                                name = displayName,
                                color = linkzAvatarColor(displayName),
                                size = 40.dp
                            )
                        }
                    }
                )
                LinkzProfileRow(
                    title = "Name",
                    value = nickname,
                    onClick = { showNicknameSheet = true }
                )
                LinkzProfileRow(
                    title = "Gender",
                    value = gender.ifBlank { "Not Set" },
                    onClick = { showGenderDialog = true }
                )
                LinkzProfileRow(
                    title = "Region",
                    value = region.ifBlank { "Not Set" },
                    onClick = { showRegionSheet = true }
                )
                LinkzProfileRow(
                    title = "Mobile Number",
                    value = "Not Linked",
                    onClick = { showPhoneDialog = true }
                )
                LinkzProfileRow(
                    title = "ID",
                    value = if (fingerprint.isNotBlank()) {
                        shortFingerprint(fingerprint)
                    } else {
                        "Not available"
                    },
                    onClick = { showIdDialog = true }
                )
                LinkzProfileRow(
                    title = "My QR Code",
                    onClick = { viewModel.showVerificationSheet() },
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = "QR code",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                LinkzProfileRow(
                    title = "My Friend Code",
                    value = "One-time",
                    onClick = onShowInviteCode,
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = "Friend code",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                LinkzProfileRow(
                    title = "What's Up",
                    value = status.ifBlank { "Not Set" },
                    onClick = { showStatusSheet = true },
                    showDivider = false
                )
            }
        }
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
    if (showGenderDialog) {
        AlertDialog(
            onDismissRequest = { showGenderDialog = false },
            title = { Text(text = "Gender") },
            text = {
                Column {
                    LinkzGenderOption(
                        label = "Male",
                        selected = gender == "Male",
                        onClick = {
                            viewModel.setProfileGender("Male")
                            showGenderDialog = false
                        }
                    )
                    LinkzGenderOption(
                        label = "Female",
                        selected = gender == "Female",
                        onClick = {
                            viewModel.setProfileGender("Female")
                            showGenderDialog = false
                        }
                    )
                    LinkzGenderOption(
                        label = "Not Set",
                        selected = gender.isBlank(),
                        onClick = {
                            viewModel.setProfileGender("")
                            showGenderDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showGenderDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
    if (showRegionSheet) {
        LinkzTextFieldSheet(
            title = "Region",
            helper = "Shown on your Profile page only. It never leaves this phone.",
            initial = region,
            label = "Region",
            allowEmpty = true,
            onDismiss = { showRegionSheet = false },
            onSave = { newRegion ->
                viewModel.setProfileRegion(newRegion)
                showRegionSheet = false
            }
        )
    }
    if (showStatusSheet) {
        LinkzTextFieldSheet(
            title = "What's Up",
            helper = "A short status line, shown on your Profile page and on Me.",
            initial = status,
            label = "What's Up",
            allowEmpty = true,
            onDismiss = { showStatusSheet = false },
            onSave = { newStatus ->
                viewModel.setProfileStatus(newStatus)
                showStatusSheet = false
            }
        )
    }
    if (showPhoneDialog) {
        AlertDialog(
            onDismissRequest = { showPhoneDialog = false },
            title = { Text(text = "Mobile Number") },
            text = {
                Text(
                    text = "Linkz has no accounts or phone numbers. " +
                        "Your identity is your Linkz ID — the fingerprint " +
                        "other phones use to find you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showPhoneDialog = false }) {
                    Text(text = "OK")
                }
            }
        )
    }
    if (showIdDialog) {
        AlertDialog(
            onDismissRequest = { showIdDialog = false },
            title = { Text(text = "Linkz ID") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "This fingerprint is how other phones find you. " +
                            "Share it, or let someone scan your QR code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Linkz ID", fingerprint)
                    )
                    showIdDialog = false
                }) {
                    Text(text = "Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIdDialog = false }) {
                    Text(text = "Close")
                }
            }
        )
    }
}

/**
 * One row of the Profile page: title on the left, value (or a
 * trailing slot, for the photo and QR code) on the right, chevron at
 * the far right, and a hairline divider under it unless it is the
 * last row of the section.
 */
@Composable
private fun LinkzProfileRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (trailing != null) {
                    trailing()
                    Spacer(modifier = Modifier.width(8.dp))
                } else if (value != null) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@Composable
private fun LinkzGenderOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/**
 * Generic one-field bottom-sheet editor, same shape as the nickname
 * sheet. Shared by the Profile page (Region, What's Up) and the Me
 * tab (+ Status pill). Saving an empty value clears the field when
 * allowEmpty is true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkzTextFieldSheet(
    title: String,
    helper: String,
    initial: String,
    label: String,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val trimmed = text.trim()
    val canSave = trimmed != initial && (allowEmpty || trimmed.isNotEmpty())

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = helper,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(text = label) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = "Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onSave(trimmed) },
                    enabled = canSave
                ) {
                    Text(text = "Save")
                }
            }
        }
    }
}
