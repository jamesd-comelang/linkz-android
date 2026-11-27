package com.bitchat.android.nostr

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of waiting for a relay's OK (NIP-20) for a published event.
 * Relay acceptance proves the relay stored the event — it says nothing about
 * the recipient reading it (Nostr has no delivery acks).
 */
sealed class RelayAckResult {
    /** At least one relay accepted the event. */
    data class Accepted(val relayUrl: String) : RelayAckResult()

    /** Every relay the event was written to rejected it. */
    data class Rejected(val reason: String) : RelayAckResult()

    /** No relay answered within the timeout. */
    object Timeout : RelayAckResult()

    /** No relay was connected when the event was sent, so nothing was written. */
    object NoRelayConnected : RelayAckResult()
}

/**
 * Tracks one-shot relay-ack callbacks by event ID.
 *
 * Pure Kotlin, no Android dependencies, no coroutines: the caller schedules the
 * timeout and drives [onOk] / [onTimeout]. Thread-safe: OKs arrive on relay
 * socket threads while registration happens on the send coroutine.
 *
 * Semantics: first acceptance wins; rejections only fire once every awaited
 * relay has rejected; a late OK after the callback fired (accept / reject /
 * timeout) is a no-op. A duplicate [register] for the same event ID is a no-op
 * (first wins).
 */
class RelayAckTracker {

    private class PendingAck(
        val relayUrls: Set<String>,
        val onAck: (RelayAckResult) -> Unit
    ) {
        val fired = AtomicBoolean(false)
        val rejectedRelays = ConcurrentHashMap.newKeySet<String>()
        val rejectionReasons = ConcurrentHashMap<String, String>()
    }

    private val pending = ConcurrentHashMap<String, PendingAck>()

    /** Register a callback for [eventId]. Returns false if one is already registered. */
    fun register(
        eventId: String,
        relayUrls: Set<String>,
        onAck: (RelayAckResult) -> Unit
    ): Boolean {
        if (relayUrls.isEmpty()) return false
        return pending.putIfAbsent(eventId, PendingAck(relayUrls, onAck)) == null
    }

    /**
     * Feed a relay OK into the tracker. Returns true if this OK fired the callback.
     */
    fun onOk(eventId: String, relayUrl: String, accepted: Boolean, message: String?): Boolean {
        val entry = pending[eventId] ?: return false
        if (!accepted) {
            entry.rejectedRelays.add(relayUrl)
            if (message != null) entry.rejectionReasons[relayUrl] = message
            if (entry.rejectedRelays.containsAll(entry.relayUrls)) {
                val reason = entry.relayUrls.sorted().joinToString("; ") { url ->
                    val reason = entry.rejectionReasons[url]
                    if (reason != null) "$url: $reason" else url
                }
                return fire(entry, eventId, RelayAckResult.Rejected(reason))
            }
            return false
        }
        return fire(entry, eventId, RelayAckResult.Accepted(relayUrl))
    }

    /** Fire [RelayAckResult.Timeout] if the callback has not fired yet. Returns true if fired. */
    fun onTimeout(eventId: String): Boolean {
        val entry = pending[eventId] ?: return false
        return fire(entry, eventId, RelayAckResult.Timeout)
    }

    private fun fire(entry: PendingAck, eventId: String, result: RelayAckResult): Boolean {
        if (!entry.fired.compareAndSet(false, true)) return false
        pending.remove(eventId)
        entry.onAck(result)
        return true
    }

    /** For tests/diagnostics only. */
    fun pendingCount(): Int = pending.size
}
