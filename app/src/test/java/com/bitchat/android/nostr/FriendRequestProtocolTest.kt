package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendRequestProtocolTest {

    private fun sampleRequest() = FriendRequestPayload(
        requestId = "req-1",
        fromPubkeyHex = "11".repeat(32),
        fromNickname = "Alice",
        fromNoiseKeyHex = "22".repeat(32),
        greeting = "Hi, I'm Alice",
        createdAtMs = 1_700_000_000_000L
    )

    @Test
    fun `request encode decode round trip preserves fields`() {
        val payload = sampleRequest()
        val decoded = FriendRequestProtocol.decodeRequest(
            FriendRequestProtocol.encodeRequest(payload)
        )
        assertEquals(payload, decoded)
    }

    @Test
    fun `request decode rejects non request content`() {
        assertNull(FriendRequestProtocol.decodeRequest("hello"))
        assertNull(FriendRequestProtocol.decodeRequest("bitchat1:aaaa"))
        assertNull(FriendRequestProtocol.decodeRequest("{not json"))
        assertNull(
            FriendRequestProtocol.decodeRequest(
                FriendRequestProtocol.REQUEST_PREFIX + "{\"v\":2,\"requestId\":\"x\",\"fromNpub\":\"ab\"}"
            )
        )
    }

    @Test
    fun `accept encode decode round trip preserves fields`() {
        val payload = FriendAcceptPayload(
            requestId = "req-1",
            fromPubkeyHex = "33".repeat(32),
            fromNickname = "Bob",
            fromNoiseKeyHex = "44".repeat(32),
            createdAtMs = 1_700_000_000_000L
        )
        val decoded = FriendRequestProtocol.decodeAccept(
            FriendRequestProtocol.encodeAccept(payload)
        )
        assertEquals(payload, decoded)
    }

    @Test
    fun `pending requests expire after three days, accepted ones do not`() {
        val now = 1_700_000_000_000L
        val fresh = FriendRequest(
            requestId = "r1",
            remotePubkeyHex = "11".repeat(32),
            remoteNickname = "Alice",
            remoteNoiseKeyHex = "22".repeat(32),
            greeting = "",
            createdAtMs = now,
            direction = FriendRequestDirection.INCOMING
        )
        assertFalse(fresh.isExpired(now))
        val oldPending = fresh.copy(
            createdAtMs = now - FriendRequestProtocol.REQUEST_VALIDITY_MS - 1
        )
        assertTrue(oldPending.isExpired(now))
        val oldAccepted = oldPending.copy(status = FriendRequestStatus.ACCEPTED)
        assertFalse(oldAccepted.isExpired(now))
    }

    @Test
    fun `invite code parsing accepts npub, nostr uri, and hex`() {
        val zeroNpub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqzqujme"
        val zeroHex = "00".repeat(32)
        assertEquals(zeroHex, InviteCodeManager.parseInviteCode(zeroNpub))
        assertEquals(zeroHex, InviteCodeManager.parseInviteCode("nostr:$zeroNpub"))
        assertEquals(zeroHex, InviteCodeManager.parseInviteCode(zeroHex.uppercase()))
        assertNull(InviteCodeManager.parseInviteCode("not a code"))
        assertNull(InviteCodeManager.parseInviteCode(""))
        assertNull(InviteCodeManager.parseInviteCode("npub1qqqq"))
    }
}
