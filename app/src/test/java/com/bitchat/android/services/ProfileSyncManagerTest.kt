package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.ProfileSync
import com.bitchat.android.ui.DataManager
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric test for the profile-sync announce bug: an announce for a
 * peer with no cached record must NOT bump the stored version, because
 * the stored version is what the next fetch request claims to have.
 * Claiming the announced version makes the sender answer with just
 * another announce instead of the photo (replyForRequest only sends
 * PROFILE_RESPONSE when its own version is newer than the claimed one),
 * so the photo never arrives and the fetch silently loops forever.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSyncManagerTest {

    private val peerID = "a".repeat(64)

    @Test
    fun `announce with no cache keeps old version so the fetch requests the photo`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataManager = DataManager(context)
        val manager = ProfileSyncManager.getInstance(context)

        // Own profile at v2, so the sender side answers a v0 request
        // with the full photo response.
        dataManager.saveNickname("Alice")
        dataManager.saveNickname("Bob")
        assertEquals(2L, manager.ownVersion())

        // No cached record for the peer yet (fresh install, first sync).
        assertNull(dataManager.loadPeerProfile(peerID))

        // The peer announces v2 with a new photo.
        val photo = ByteArray(1024) { (it % 251).toByte() }
        val hash = ProfileSync.sha256Hex(photo)
        val announceBytes = ProfileSync.encodeAnnounce(2L, hash, "Alice")!!
        val announce = ProfileSync.decodeAnnounce(announceBytes)!!

        manager.onProfileAnnounce(peerID, announce)

        // The nickname is cached, but the version stays at the OLD value:
        // claiming v2 here is the bug — the sender would only re-announce.
        val cached = dataManager.loadPeerProfile(peerID)
        assertEquals("Alice", cached?.nickname)
        assertEquals(0L, cached?.version)
        assertNull(cached?.photoHashHex)

        // The request B would now send claims v0, so A answers with the photo.
        val (typeForOld, _) = manager.replyForRequest(peerID, cached?.version ?: 0L)!!
        assertEquals(NoisePayloadType.PROFILE_RESPONSE, typeForOld)

        // Control: claiming the announced version only gets another announce.
        val (typeForNew, _) = manager.replyForRequest(peerID, 2L)!!
        assertEquals(NoisePayloadType.PROFILE_ANNOUNCE, typeForNew)
    }

    private fun relationship(
        noiseKey: ByteArray,
        isFavorite: Boolean,
        theyFavoritedUs: Boolean
    ) = FavoriteRelationship(
        peerNoisePublicKey = noiseKey,
        peerNostrPublicKey = null,
        peerNickname = "Bob",
        isFavorite = isFavorite,
        theyFavoritedUs = theyFavoritedUs,
        favoritedAt = Date(),
        lastUpdated = Date()
    )

    /**
     * Robolectric test for the announce-recipients bug: the profile-change
     * announce was pushed only to DataManager.favoritePeers, which the
     * Friend Code accept path never writes — it only updates
     * FavoritesPersistenceService. So for every friend added via Friend
     * Code the recipient set was empty, the announce never left the phone,
     * and their photo never synced even though DMs worked (they gate on
     * the persistence service, a separate store).
     */
    @Test
    fun `announce recipients include mutual favorites when DataManager favorites are empty`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataManager = DataManager(context)
        val manager = ProfileSyncManager.getInstance(context)

        // Precondition: no manually starred peers — exactly the state of a
        // friend added via Friend Code, which never calls addFavorite.
        // With the old recipients (DataManager favorites only) the announce
        // would go nowhere.
        assertTrue(dataManager.favoritePeers.isEmpty())

        val noiseKey = ByteArray(32) { (it * 7 + 3).toByte() }
        val mutual = relationship(noiseKey, isFavorite = true, theyFavoritedUs = true)

        val recipients = manager.announceRecipientPeerIDs(emptySet(), listOf(mutual))
        assertEquals(setOf(ContactIdentityResolver.noiseKeyHex(noiseKey)), recipients)
    }

    @Test
    fun `announce recipients exclude non-mutual favorites`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ProfileSyncManager.getInstance(context)

        val noiseKey = ByteArray(32) { (it * 7 + 3).toByte() }
        // We favorited them but they did not favorite us back: not mutual,
        // so no announce — same set DMs require.
        val oneWay = relationship(noiseKey, isFavorite = true, theyFavoritedUs = false)

        assertTrue(manager.announceRecipientPeerIDs(emptySet(), listOf(oneWay)).isEmpty())
    }

    @Test
    fun `announce recipients keep manually starred peers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ProfileSyncManager.getInstance(context)

        val recipients = manager.announceRecipientPeerIDs(setOf("0123456789abcdef"), emptyList())
        assertEquals(setOf("0123456789abcdef"), recipients)
    }
}
