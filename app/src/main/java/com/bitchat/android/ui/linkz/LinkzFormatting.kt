package com.bitchat.android.ui.linkz

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure presentation helpers for the Linkz shell. No Android or Compose
 * dependencies, so they are covered by plain JVM unit tests.
 */

/** A person shown on the Contacts tab, already resolved to a display name. */
data class LinkzContact(
    val id: String,
    val displayName: String,
    val isFavorite: Boolean = false,
    val isConnected: Boolean = false,
    val fingerprint: String? = null,
    val isVerified: Boolean = false,
    val isBlocked: Boolean = false,
    val isMuted: Boolean = false,
    /** Peer id to use for favorite/block/verify actions; defaults to [id]. */
    val peerID: String = id,
    /** Conversation id for message/mute actions, when this contact has one. */
    val conversationID: String? = null
)

/**
 * Dedupe contacts by id (case-insensitive), keeping the richest entry for
 * each id, then sort favorites first, connected peers next, then by name.
 */
fun dedupeLinkzContacts(contacts: List<LinkzContact>): List<LinkzContact> {
    val byId = linkedMapOf<String, LinkzContact>()
    for (contact in contacts) {
        val key = contact.id.lowercase()
        val existing = byId[key]
        if (existing == null) {
            byId[key] = contact
        } else {
            val betterName = contact.displayName.ifBlank { existing.displayName }
            byId[key] = existing.copy(
                displayName = betterName,
                isFavorite = existing.isFavorite || contact.isFavorite,
                isConnected = existing.isConnected || contact.isConnected,
                fingerprint = existing.fingerprint ?: contact.fingerprint,
                isVerified = existing.isVerified || contact.isVerified,
                isBlocked = existing.isBlocked || contact.isBlocked,
                isMuted = existing.isMuted || contact.isMuted,
                peerID = contact.peerID.takeIf { it != contact.id } ?: existing.peerID,
                conversationID = existing.conversationID ?: contact.conversationID
            )
        }
    }
    return byId.values.sortedWith(
        compareByDescending<LinkzContact> { it.isFavorite }
            .thenByDescending { it.isConnected }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.id.lowercase() }
    )
}

/**
 * WeChat-style list timestamp: time today, "Yesterday" yesterday, month/day
 * within the year, full date otherwise. Times are in the device timezone.
 */
fun formatChatTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
        date == today -> {
            val time = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalTime()
            DateTimeFormatter.ofPattern("HH:mm").format(time)
        }
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> DateTimeFormatter.ofPattern("MM/dd").format(date)
        else -> DateTimeFormatter.ofPattern("yyyy/MM/dd").format(date)
    }
}

/** Short fingerprint for the Me tab, e.g. the first 12 characters. */
fun shortFingerprint(fingerprint: String): String =
    fingerprint.takeIf { it.isNotBlank() }?.take(12) ?: ""
