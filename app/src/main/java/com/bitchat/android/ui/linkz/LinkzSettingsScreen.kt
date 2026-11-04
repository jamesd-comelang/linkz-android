package com.bitchat.android.ui.linkz

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.ui.LanguagePreferenceManager
import com.bitchat.android.ui.theme.ThemePreference
import com.bitchat.android.ui.theme.ThemePreferenceManager

/**
 * Device-local Settings page, WeChat-style: everything here lives on
 * this phone. About, debug, and clear-all-data used to sit on the Me
 * tab itself; they moved here so Me stays a profile, not a junk
 * drawer. The QR code is not listed under About: it lives in the
 * verification sheet, and the old Me row promised it wrongly.
 */
@Composable
fun LinkzSettingsScreen(
    onBack: () -> Unit,
    onShowDebug: () -> Unit,
    onShowAbout: () -> Unit,
    onShowPanicDialog: () -> Unit,
    onShowBlocked: () -> Unit
) {
    val context = LocalContext.current
    val theme by ThemePreferenceManager.themeFlow.collectAsStateWithLifecycle()
    var showLanguageDialog by remember { mutableStateOf(false) }
    val currentLanguageTag = LanguagePreferenceManager.currentLanguageTag()
    val languages = remember {
        runCatching { LanguagePreferenceManager.supportedLanguages(context) }
            .getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        LinkzTopBar(title = "Settings", onBackClick = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "Appearance")
        LinkzRadioRow(
            title = "System default",
            selected = theme == ThemePreference.System,
            onClick = { ThemePreferenceManager.set(context, ThemePreference.System) }
        )
        LinkzRadioRow(
            title = "Light",
            selected = theme == ThemePreference.Light,
            onClick = { ThemePreferenceManager.set(context, ThemePreference.Light) }
        )
        LinkzRadioRow(
            title = "Dark",
            selected = theme == ThemePreference.Dark,
            onClick = { ThemePreferenceManager.set(context, ThemePreference.Dark) }
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "Language")
        LinkzMenuRow(
            title = "App language",
            subtitle = languages
                .firstOrNull { it.languageTag == currentLanguageTag }?.endonym
                ?: "System default",
            onClick = { showLanguageDialog = true }
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "Notifications")
        LinkzMenuRow(
            title = "Notifications",
            subtitle = "Open system settings",
            onClick = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "Privacy")
        LinkzMenuRow(
            title = "Blocked contacts",
            subtitle = "Unblock in Contacts",
            onClick = onShowBlocked
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinkzSectionHeader(text = "App")
        LinkzMenuRow(
            title = "About Linkz",
            subtitle = "App info",
            onClick = onShowAbout
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

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(text = "App language") },
            text = {
                Column {
                    LinkzLanguageRow(
                        label = "System default",
                        selected = currentLanguageTag.isBlank(),
                        onClick = {
                            LanguagePreferenceManager.setLanguage("")
                            showLanguageDialog = false
                        }
                    )
                    for (language in languages) {
                        LinkzLanguageRow(
                            label = language.endonym,
                            selected = language.languageTag == currentLanguageTag,
                            onClick = {
                                LanguagePreferenceManager.setLanguage(language.languageTag)
                                showLanguageDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

@Composable
private fun LinkzRadioRow(
    title: String,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun LinkzLanguageRow(
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
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
