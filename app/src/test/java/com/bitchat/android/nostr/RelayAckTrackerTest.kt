package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayAckTrackerTest {

    @Test
    fun `accept fires once with the accepting relay`() {
        val tracker = RelayAckTracker()
        val results = mutableListOf<RelayAckResult>()
        assertTrue(tracker.register("e1", setOf("wss://a", "wss://b")) { results.add(it) })
        assertTrue(tracker.onOk("e1", "wss://b", accepted = true, message = null))
        assertEquals(listOf(RelayAckResult.Accepted("wss://b")), results)
        // A late OK after firing is a no-op.
        assertFalse(tracker.onOk("e1", "wss://a", accepted = true, message = null))
        assertEquals(1, results.size)
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun `rejection fires only after every awaited relay rejects`() {
        val tracker = RelayAckTracker()
        val results = mutableListOf<RelayAckResult>()
        tracker.register("e1", setOf("wss://a", "wss://b")) { results.add(it) }
        assertFalse(tracker.onOk("e1", "wss://a", accepted = false, message = "pow required"))
        assertTrue(results.isEmpty())
        assertTrue(tracker.onOk("e1", "wss://b", accepted = false, message = "duplicate"))
        assertEquals(1, results.size)
        val rejected = results[0] as RelayAckResult.Rejected
        assertTrue(rejected.reason.contains("pow required"))
        assertTrue(rejected.reason.contains("duplicate"))
    }

    @Test
    fun `a rejection from an unawaited relay does not fire`() {
        val tracker = RelayAckTracker()
        val results = mutableListOf<RelayAckResult>()
        tracker.register("e1", setOf("wss://a")) { results.add(it) }
        assertFalse(tracker.onOk("e1", "wss://other", accepted = false, message = "nope"))
        assertTrue(results.isEmpty())
        assertEquals(1, tracker.pendingCount())
    }

    @Test
    fun `timeout fires once and a late OK is ignored`() {
        val tracker = RelayAckTracker()
        val results = mutableListOf<RelayAckResult>()
        tracker.register("e1", setOf("wss://a")) { results.add(it) }
        assertTrue(tracker.onTimeout("e1"))
        assertEquals(listOf(RelayAckResult.Timeout), results)
        assertFalse(tracker.onOk("e1", "wss://a", accepted = true, message = null))
        assertEquals(1, results.size)
    }

    @Test
    fun `duplicate register is a no-op and keeps the first callback`() {
        val tracker = RelayAckTracker()
        val first = mutableListOf<RelayAckResult>()
        val second = mutableListOf<RelayAckResult>()
        assertTrue(tracker.register("e1", setOf("wss://a")) { first.add(it) })
        assertFalse(tracker.register("e1", setOf("wss://a")) { second.add(it) })
        tracker.onOk("e1", "wss://a", accepted = true, message = null)
        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `ok and timeout for unknown event ids are no-ops`() {
        val tracker = RelayAckTracker()
        assertFalse(tracker.onOk("nope", "wss://a", accepted = true, message = null))
        assertFalse(tracker.onTimeout("nope"))
    }

    @Test
    fun `empty relay set cannot register`() {
        val tracker = RelayAckTracker()
        assertFalse(tracker.register("e1", emptySet()) {})
        assertEquals(0, tracker.pendingCount())
    }
}
