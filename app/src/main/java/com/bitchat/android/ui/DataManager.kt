package com.bitchat.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bitchat.android.geohash.DEFAULT_LIVE_LOCATION_ENABLED
import com.bitchat.android.nostr.FriendRequest
import com.google.gson.Gson
import kotlin.random.Random

/**
 * Handles data persistence operations for the chat system
 */
class DataManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DataManager"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Channel-related maps that need to persist state
    private val _channelCreators = mutableMapOf<String, String>()
    private val _favoritePeers = mutableSetOf<String>()
    private val _blockedUsers = mutableSetOf<String>()
    private val _channelMembers = mutableMapOf<String, MutableSet<String>>()
    
    val channelCreators: Map<String, String> get() = _channelCreators
    val favoritePeers: Set<String> get() = _favoritePeers
    val blockedUsers: Set<String> get() = _blockedUsers
    val channelMembers: Map<String, MutableSet<String>> get() = _channelMembers
    
    // MARK: - Nickname Management
    
    fun loadNickname(): String {
        val savedNickname = prefs.getString("nickname", null)
        return if (savedNickname != null) {
            savedNickname
        } else {
            val randomNickname = "anon${Random.nextInt(1000, 9999)}"
            saveNickname(randomNickname)
            randomNickname
        }
    }
    
    fun saveNickname(nickname: String) {
        val previous = prefs.getString("nickname", null)
        prefs.edit().putString("nickname", nickname).apply()
        // The nickname is part of the synced profile: a change makes the
        // local profile newer, so peers re-fetch it in the background.
        if (previous != nickname) bumpProfileVersion()
    }

    // MARK: - Profile Setup (WeChat-style nickname + photo at first run)

    /** True once the user has finished or skipped the one-time profile setup. */
    fun isProfileSetupDone(): Boolean {
        return prefs.getBoolean("profile_setup_done", false)
    }

    fun setProfileSetupDone() {
        prefs.edit().putBoolean("profile_setup_done", true).apply()
    }

    /**
     * Own profile photo, local only. The mesh carries nickname + fingerprint
     * but no photo, so other peers always see the initial-based avatar.
     */
    fun saveAvatarBitmap(bitmap: android.graphics.Bitmap): Boolean {
        return try {
            context.openFileOutput("avatar.jpg", Context.MODE_PRIVATE).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
            prefs.edit().putString("avatar_path", "avatar.jpg").apply()
            // The photo is part of the synced profile: a new photo makes the
            // local profile newer, so peers re-fetch it in the background.
            refreshProfilePhotoHash()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Raw JPEG bytes of the own profile photo, or null when there is no photo. */
    fun getProfilePhotoBytes(): ByteArray? {
        if (prefs.getString("avatar_path", null) == null) return null
        return try {
            context.openFileInput("avatar.jpg").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun loadAvatarBitmap(): android.graphics.Bitmap? {
        val name = prefs.getString("avatar_path", null) ?: return null
        return try {
            context.openFileInput(name).use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decode a picked image, downscale it to at most 512px on a side,
     * and save it as the own profile photo. Same downscale the
     * onboarding profile setup uses, reused so the photo can be
     * changed later from the Me tab.
     */
    fun saveAvatarFromUri(uri: android.net.Uri): Boolean {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > 512 ||
                bounds.outHeight / sample > 512
            ) {
                sample *= 2
            }
            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input, null, options)
            } ?: return false
            saveAvatarBitmap(bitmap)
        } catch (_: Exception) {
            false
        }
    }
    
    // MARK: - Profile Sync (WeChat-style lazy avatar sync)

    /**
     * Own profile version. Bumped on every nickname or photo change; peers
     * compare it against their cached copy and fetch only when it is newer.
     * Starts at 0, so a peer with no cached copy always fetches once.
     */
    fun getProfileVersion(): Long {
        return prefs.getLong("profile_version", 0L)
    }

    fun bumpProfileVersion(): Long {
        val next = getProfileVersion() + 1
        prefs.edit().putLong("profile_version", next).apply()
        return next
    }

    /** Lowercase hex SHA-256 of the own profile photo JPEG, or null with no photo. */
    fun getProfilePhotoHashHex(): String? {
        return prefs.getString("profile_photo_hash", null)
    }

    /**
     * Recompute the photo hash from the saved JPEG. Bumps the profile version
     * only when the hash actually changed, so re-saving the same photo does
     * not make peers re-fetch it.
     */
    fun refreshProfilePhotoHash(): String? {
        val bytes = getProfilePhotoBytes()
        val hash = bytes?.let { com.bitchat.android.model.ProfileSync.sha256Hex(it) }
        val previous = getProfilePhotoHashHex()
        if (hash == null) {
            prefs.edit().remove("profile_photo_hash").apply()
        } else {
            prefs.edit().putString("profile_photo_hash", hash).apply()
        }
        if (hash != previous) bumpProfileVersion()
        return hash
    }

    /** One peer's cached profile (nickname + version + photo hash). The photo itself lives in a file. */
    data class PeerProfileRecord(
        val peerID: String,
        val nickname: String,
        val version: Long,
        val photoHashHex: String?,
        val updatedAtMs: Long
    )

    private fun peerProfileKey(peerID: String) = "peer_profile_$peerID"

    private fun peerPhotoFileName(peerID: String) = "peer_photo_$peerID.jpg"

    fun loadPeerProfile(peerID: String): PeerProfileRecord? {
        val json = prefs.getString(peerProfileKey(peerID), null) ?: return null
        return try {
            gson.fromJson(json, PeerProfileRecord::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cache a peer's fetched profile. Photo bytes are stored in a file so the
     * (up to 256 KiB) JPEG never sits in SharedPreferences. A null [photoBytes]
     * keeps any previously cached photo file (the peer has no photo).
     */
    fun savePeerProfile(
        peerID: String,
        nickname: String,
        version: Long,
        photoHashHex: String?,
        photoBytes: ByteArray?
    ) {
        if (photoBytes != null) {
            try {
                context.openFileOutput(peerPhotoFileName(peerID), Context.MODE_PRIVATE).use { out ->
                    out.write(photoBytes)
                }
            } catch (_: Exception) {
                return
            }
        } else {
            // The peer has no photo now: drop any previously cached one, or the
            // old photo would keep showing next to the new (photoless) profile.
            try {
                context.deleteFile(peerPhotoFileName(peerID))
            } catch (_: Exception) {
            }
        }
        val record = PeerProfileRecord(peerID, nickname, version, photoHashHex, System.currentTimeMillis())
        prefs.edit().putString(peerProfileKey(peerID), gson.toJson(record)).apply()
    }

    /** Decoded cached photo of a peer, or null when none was ever fetched. */
    fun loadPeerPhotoBitmap(peerID: String): android.graphics.Bitmap? {
        return try {
            context.openFileInput(peerPhotoFileName(peerID)).use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Raw cached photo bytes of a peer, or null when none was ever fetched. */
    fun getProfilePhotoBytesForPeer(peerID: String): ByteArray? {
        return try {
            context.openFileInput(peerPhotoFileName(peerID)).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun deletePeerProfile(peerID: String) {
        prefs.edit().remove(peerProfileKey(peerID)).apply()
        try {
            context.deleteFile(peerPhotoFileName(peerID))
        } catch (_: Exception) {
        }
    }

    // MARK: - Geohash Channel Persistence
    
    fun loadLastGeohashChannel(): String? {
        return prefs.getString("last_geohash_channel", null)
    }
    
    fun saveLastGeohashChannel(channelData: String) {
        prefs.edit().putString("last_geohash_channel", channelData).apply()
        Log.d(TAG, "Saved last geohash channel")
    }
    
    fun clearLastGeohashChannel() {
        prefs.edit().remove("last_geohash_channel").apply()
        Log.d(TAG, "Cleared last geohash channel")
    }

    // MARK: - Location Services State
    
    fun saveLocationServicesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("location_services_enabled", enabled).commit()
        Log.d(TAG, "Saved location services enabled state: $enabled")
    }
    
    fun isLocationServicesEnabled(): Boolean {
        return prefs.getBoolean("location_services_enabled", DEFAULT_LIVE_LOCATION_ENABLED)
    }
    
    // MARK: - Channel Data Management
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        // Load joined channels
        val savedChannels = prefs.getStringSet("joined_channels", emptySet()) ?: emptySet()
        
        // Load password protected channels
        val savedProtectedChannels = prefs.getStringSet("password_protected_channels", emptySet()) ?: emptySet()
        
        // Load channel creators
        val creatorsJson = prefs.getString("channel_creators", "{}")
        try {
            val creatorsMap = gson.fromJson(creatorsJson, Map::class.java) as? Map<String, String>
            creatorsMap?.let { _channelCreators.putAll(it) }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
        
        // Initialize channel members for loaded channels
        savedChannels.forEach { channel ->
            if (!_channelMembers.containsKey(channel)) {
                _channelMembers[channel] = mutableSetOf()
            }
        }
        
        return Pair(savedChannels, savedProtectedChannels)
    }
    
    fun saveChannelData(joinedChannels: Set<String>, passwordProtectedChannels: Set<String>) {
        prefs.edit().apply {
            putStringSet("joined_channels", joinedChannels)
            putStringSet("password_protected_channels", passwordProtectedChannels)
            putString("channel_creators", gson.toJson(_channelCreators))
            apply()
        }
    }
    
    fun addChannelCreator(channel: String, creatorID: String) {
        _channelCreators[channel] = creatorID
    }
    
    fun removeChannelCreator(channel: String) {
        _channelCreators.remove(channel)
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return _channelCreators[channel] == peerID
    }
    
    // MARK: - Channel Members Management
    
    fun addChannelMember(channel: String, peerID: String) {
        if (!_channelMembers.containsKey(channel)) {
            _channelMembers[channel] = mutableSetOf()
        }
        _channelMembers[channel]?.add(peerID)
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        _channelMembers[channel]?.remove(peerID)
    }
    
    fun removeChannelMembers(channel: String) {
        _channelMembers.remove(channel)
    }
    
    fun cleanupDisconnectedMembers(channel: String, connectedPeers: List<String>, myPeerID: String) {
        _channelMembers[channel]?.removeAll { memberID ->
            memberID != myPeerID && !connectedPeers.contains(memberID)
        }
    }
    
    fun cleanupAllDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        _channelMembers.values.forEach { members ->
            members.removeAll { memberID ->
                memberID != myPeerID && !connectedPeers.contains(memberID)
            }
        }
    }
    
    // MARK: - Favorites Management
    
    fun loadFavorites() {
        val savedFavorites = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        _favoritePeers.addAll(savedFavorites)
        Log.d(TAG, "Loaded ${savedFavorites.size} favorite users from storage: $savedFavorites")
    }
    
    fun saveFavorites() {
        prefs.edit().putStringSet("favorites", _favoritePeers).apply()
        Log.d(TAG, "Saved ${_favoritePeers.size} favorite users to storage: $_favoritePeers")
    }

    // MARK: - One-time invite friend requests (New Friends inbox)

    /** Persisted friend requests, newest last. Expired is derived at read time, never stored. */
    fun loadFriendRequests(): List<FriendRequest> {
        val json = prefs.getString("friend_requests", null) ?: return emptyList()
        return try {
            gson.fromJson(json, Array<FriendRequest>::class.java)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveFriendRequests(requests: List<FriendRequest>) {
        prefs.edit().putString("friend_requests", gson.toJson(requests)).apply()
    }
    
    fun addFavorite(fingerprint: String) {
        val wasAdded = _favoritePeers.add(fingerprint)
        Log.d(TAG, "addFavorite: fingerprint=$fingerprint, wasAdded=$wasAdded")
        saveFavorites()
        logAllFavorites()
    }
    
    fun removeFavorite(fingerprint: String) {
        val wasRemoved = _favoritePeers.remove(fingerprint)
        Log.d(TAG, "removeFavorite: fingerprint=$fingerprint, wasRemoved=$wasRemoved")
        saveFavorites()
        logAllFavorites()
    }
    
    fun isFavorite(fingerprint: String): Boolean {
        val result = _favoritePeers.contains(fingerprint)
        Log.d(TAG, "isFavorite check: fingerprint=$fingerprint, result=$result")
        return result
    }

    // MARK: - Remark Names (local only, like WeChat remarks)

    /**
     * A remark is a name only this phone shows for a peer. It never
     * leaves the device, so it needs no protocol change. Keyed by
     * peer id, the same key the Contacts list uses for favorite and
     * block actions.
     */
    fun getRemark(peerID: String): String? {
        return prefs.getString("remark_$peerID", null)?.takeIf { it.isNotBlank() }
    }

    fun setRemark(peerID: String, remark: String) {
        if (remark.isBlank()) {
            prefs.edit().remove("remark_$peerID").apply()
        } else {
            prefs.edit().putString("remark_$peerID", remark).apply()
        }
    }

    // MARK: - Own Profile Fields (local only, like WeChat profile rows)

    /**
     * Gender, region, and status ("What's Up") are shown on the own
     * Profile page only. They never leave this phone: the mesh carries
     * nickname + fingerprint, nothing else about the owner. Blank
     * clears the field, the same as remarks.
     */
    fun getProfileGender(): String {
        return prefs.getString("profile_gender", "") ?: ""
    }

    fun setProfileGender(gender: String) {
        if (gender.isBlank()) {
            prefs.edit().remove("profile_gender").apply()
        } else {
            prefs.edit().putString("profile_gender", gender).apply()
        }
    }

    fun getProfileRegion(): String {
        return prefs.getString("profile_region", "") ?: ""
    }

    fun setProfileRegion(region: String) {
        if (region.isBlank()) {
            prefs.edit().remove("profile_region").apply()
        } else {
            prefs.edit().putString("profile_region", region).apply()
        }
    }

    fun getProfileStatus(): String {
        return prefs.getString("profile_status", "") ?: ""
    }

    fun setProfileStatus(status: String) {
        if (status.isBlank()) {
            prefs.edit().remove("profile_status").apply()
        } else {
            prefs.edit().putString("profile_status", status).apply()
        }
    }
    
    fun logAllFavorites() {
        Log.i(TAG, "=== ALL FAVORITE USERS ===")
        Log.i(TAG, "Total favorites: ${_favoritePeers.size}")
        _favoritePeers.forEach { fingerprint ->
            Log.i(TAG, "Favorite fingerprint: $fingerprint")
        }
        Log.i(TAG, "========================")
    }
    
    // MARK: - Blocked Users Management
    
    @Synchronized
    fun loadBlockedUsers() {
        val savedBlockedUsers = prefs.getStringSet("blocked_users", emptySet()) ?: emptySet()
        _blockedUsers.clear()
        _blockedUsers.addAll(savedBlockedUsers)
    }
    
    fun saveBlockedUsers() {
        prefs.edit().putStringSet("blocked_users", _blockedUsers).apply()
    }
    
    fun addBlockedUser(fingerprint: String) {
        _blockedUsers.add(fingerprint)
        saveBlockedUsers()
    }
    
    fun removeBlockedUser(fingerprint: String) {
        _blockedUsers.remove(fingerprint)
        saveBlockedUsers()
    }
    
    @Synchronized
    fun isUserBlocked(fingerprint: String): Boolean {
        return _blockedUsers.contains(fingerprint)
    }
    
    // MARK: - Geohash Blocked Users Management
    
    private val _geohashBlockedUsers = mutableSetOf<String>() // Set of nostr pubkey hex
    val geohashBlockedUsers: Set<String> get() = _geohashBlockedUsers.toSet()
    
    @Synchronized
    fun loadGeohashBlockedUsers() {
        val savedGeohashBlockedUsers = prefs.getStringSet("geohash_blocked_users", emptySet()) ?: emptySet()
        _geohashBlockedUsers.clear()
        _geohashBlockedUsers.addAll(savedGeohashBlockedUsers)
    }
    
    fun saveGeohashBlockedUsers() {
        prefs.edit().putStringSet("geohash_blocked_users", _geohashBlockedUsers).apply()
    }
    
    @Synchronized
    fun addGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.add(pubkeyHex)
        saveGeohashBlockedUsers()
    }
    
    @Synchronized
    fun removeGeohashBlockedUser(pubkeyHex: String) {
        _geohashBlockedUsers.remove(pubkeyHex)
        saveGeohashBlockedUsers()
    }
    
    @Synchronized
    fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return _geohashBlockedUsers.contains(pubkeyHex)
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllData() {
        _channelCreators.clear()
        _favoritePeers.clear()
        _blockedUsers.clear()
        _geohashBlockedUsers.clear()
        _channelMembers.clear()
        prefs.edit().clear().apply()
        // Peer profile records live in prefs (cleared above); the cached
        // photos are files, so delete them explicitly.
        try {
            context.fileList()
                .filter { it.startsWith("peer_photo_") }
                .forEach { context.deleteFile(it) }
        } catch (_: Exception) {
        }
    }
}
