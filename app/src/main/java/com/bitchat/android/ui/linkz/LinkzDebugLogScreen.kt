package com.bitchat.android.ui.linkz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug Logs: this app's own log lines, so a bug can be grabbed on the
 * phone without adb. Reads the log with `logcat -d` (an app can read its
 * own logs, no permission needed) and shows the last 500 lines.
 *
 * Copy copies everything shown. Clear only clears this view, not the
 * phone's log buffer — Android does not let a normal app wipe it.
 * Refresh pulls fresh lines. Search filters the shown lines by text
 * (case-insensitive); Copy then copies only the matching lines.
 */
@Composable
fun LinkzDebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Lines matching the search, case-insensitive. Empty query shows all.
    val searching = query.isNotBlank()
    val displayedText: String
    val matchCount: Int
    if (searching) {
        val matches = logText.lines().filter { it.contains(query, ignoreCase = true) }
        matchCount = matches.size
        displayedText = matches.joinToString("\n")
    } else {
        matchCount = 0
        displayedText = logText
    }

    fun load() {
        scope.launch {
            loading = true
            error = null
            copied = false
            readAppLog()
                .onSuccess { logText = it; cleared = false }
                .onFailure { error = it.message ?: "Couldn't read the log." }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize()) {
        LinkzTopBar(title = "Debug Logs", onBackClick = onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Linkz debug log", displayedText)
                )
                copied = true
            }) {
                Text(text = if (copied) "Copied" else "Copy")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = {
                logText = ""
                cleared = true
            }) {
                Text(text = "Clear")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { load() }) {
                Text(text = "Refresh")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(text = "Search logs") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search"
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search"
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (searching) {
                val countText = if (matchCount == 0) {
                    "No matches"
                } else if (matchCount == 1) {
                    "1 match"
                } else {
                    "$matchCount matches"
                }
                "This app's own logs, last 500 lines. $countText."
            } else {
                "This app's own logs, last 500 lines. Clear only clears this view."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        val scrollState = rememberScrollState()
        val loadError = error
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            when {
                loading -> CircularProgressIndicator()
                loadError != null -> Text(text = loadError)
                logText.isBlank() -> Text(
                    text = if (cleared) {
                        "Log cleared. Tap Refresh to load new lines."
                    } else {
                        "No logs yet. Tap Refresh to try again."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Text(
                    text = if (searching && matchCount == 0) {
                        "No matches for \"$query\"."
                    } else {
                        displayedText
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** Last 500 lines of this app's own log, via logcat. No permission needed. */
private suspend fun readAppLog(): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "logcat", "-d", "-v", "threadtime",
                "--pid=${Process.myPid()}", "-t", "500"
            )
        )
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }
}
