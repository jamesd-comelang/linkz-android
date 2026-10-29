package com.bitchat.android.ui

import com.bitchat.android.ui.linkz.LinkzContact
import com.bitchat.android.ui.linkz.dedupeLinkzContacts
import com.bitchat.android.ui.linkz.formatChatTime
import com.bitchat.android.ui.linkz.shortFingerprint
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkzFormattingTest {

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun chatTimeTodayShowsTime() {
        val now = LocalDateTime.of(2026, 10, 29, 14, 30)
        assertEquals("14:05", formatChatTime(millis(LocalDateTime.of(2026, 10, 29, 14, 5)), millis(now)))
    }

    @Test
    fun chatTimeYesterdayShowsYesterday() {
        val now = LocalDateTime.of(2026, 10, 29, 9, 0)
        assertEquals("Yesterday", formatChatTime(millis(LocalDateTime.of(2026, 10, 28, 20, 0)), millis(now)))
    }

    @Test
    fun chatTimeEarlierThisYearShowsMonthDay() {
        val now = LocalDateTime.of(2026, 10, 29, 9, 0)
        assertEquals("03/04", formatChatTime(millis(LocalDateTime.of(2026, 3, 4, 10, 0)), millis(now)))
    }

    @Test
    fun chatTimeLastYearShowsFullDate() {
        val now = LocalDateTime.of(2026, 10, 29, 9, 0)
        assertEquals("2025/12/31", formatChatTime(millis(LocalDateTime.of(2025, 12, 31, 10, 0)), millis(now)))
    }

    @Test
    fun contactsDedupeKeepsRichestAndSortsFavoritesFirst() {
        val contacts = listOf(
            LinkzContact(id = "peer-a", displayName = "Amy"),
            LinkzContact(id = "PEER-A", displayName = "Amy", isFavorite = true),
            LinkzContact(id = "peer-b", displayName = "Bo", isConnected = true),
            LinkzContact(id = "peer-c", displayName = "Cy")
        )
        val result = dedupeLinkzContacts(contacts)
        assertEquals(listOf("peer-a", "peer-b", "peer-c"), result.map { it.id })
        assertEquals(true, result[0].isFavorite)
        assertEquals(true, result[1].isConnected)
    }

    @Test
    fun shortFingerprintTakesTwelveChars() {
        assertEquals("abcdef123456", shortFingerprint("abcdef1234567890"))
        assertEquals("", shortFingerprint(""))
    }
}
