package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Identity announcement structure with TLV encoding
 * Compatible with iOS AnnouncementPacket TLV format
 */
@Parcelize
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,    // Noise static public key (Curve25519.KeyAgreement)
    val signingPublicKey: ByteArray,  // Ed25519 public key for signing
    val capabilities: PeerCapabilities? = null,
    /** Profile version, for lazy profile sync. Null when the sender has none (old build). */
    val profileVersion: Long? = null,
    /** SHA-256 of the sender's profile photo JPEG, or null when the sender has no photo. */
    val profilePhotoHash: ByteArray? = null,
    val unknownTLVs: List<UnknownAnnouncementTLV> = emptyList()
) : Parcelable {

    /**
     * TLV types matching iOS implementation
     */
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u),  // NEW: Ed25519 signing public key
        CAPABILITIES(0x05u),
        /** Profile sync: 8-byte big-endian profile version. Old clients keep it as unknown. */
        PROFILE_VERSION(0x06u),
        /** Profile sync: 32 raw SHA-256 bytes of the profile photo. Old clients keep it as unknown. */
        PROFILE_PHOTO_HASH(0x07u);
        
        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data matching iOS implementation
     */
    fun encode(): ByteArray? {
        val nicknameData = nickname.toByteArray(Charsets.UTF_8)
        
        // Check size limits
        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255 ||
            unknownTLVs.any { it.value.size > 255 }) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for nickname
        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())
        
        // TLV for noise public key
        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())
        
        // TLV for signing public key
        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())

        // Optional little-endian feature bitfield. Old clients skip this TLV.
        capabilities?.encoded()?.let { capabilityBytes ->
            result.add(TLVType.CAPABILITIES.value.toByte())
            result.add(capabilityBytes.size.toByte())
            result.addAll(capabilityBytes.toList())
        }

        // Profile sync advertisement. Old clients decode these as unknown
        // TLVs and preserve them on re-encode, so the versions stay visible
        // to newer peers even when relayed by an old build.
        profileVersion?.let { version ->
            val versionBytes = com.bitchat.android.model.ProfileSync.encodeVersionTlvValue(version)
            result.add(TLVType.PROFILE_VERSION.value.toByte())
            result.add(versionBytes.size.toByte())
            result.addAll(versionBytes.toList())
        }
        profilePhotoHash?.let { hash ->
            if (hash.size == com.bitchat.android.model.ProfileSync.PHOTO_HASH_BYTES) {
                result.add(TLVType.PROFILE_PHOTO_HASH.value.toByte())
                result.add(hash.size.toByte())
                result.addAll(hash.toList())
            }
        }

        // Preserve extensions this build does not understand. This includes
        // gossip TLV 0x04 when an announcement is decoded through this model.
        unknownTLVs.forEach { tlv ->
            result.add(tlv.type.toByte())
            result.add(tlv.value.size.toByte())
            result.addAll(tlv.value.toList())
        }
        
        return result.toByteArray()
    }
    
    companion object {
        /**
         * Decode from TLV binary data matching iOS implementation
         */
        fun decode(data: ByteArray): IdentityAnnouncement? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null
            var capabilities: PeerCapabilities? = null
            var profileVersion: Long? = null
            var profilePhotoHash: ByteArray? = null
            val unknownTLVs = mutableListOf<UnknownAnnouncementTLV>()
            
            while (offset + 2 <= dataCopy.size) {
                // Read TLV type
                val typeValue = dataCopy[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1
                
                // Read TLV length
                val length = dataCopy[offset].toUByte().toInt()
                offset += 1
                
                // Check bounds
                if (offset + length > dataCopy.size) return null
                
                // Read TLV value
                val value = dataCopy.sliceArray(offset until offset + length)
                offset += length
                
                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = String(value, Charsets.UTF_8)
                    }
                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }
                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }
                    TLVType.CAPABILITIES -> {
                        capabilities = PeerCapabilities.decode(value)
                    }
                    TLVType.PROFILE_VERSION -> {
                        profileVersion = com.bitchat.android.model.ProfileSync.decodeVersionTlvValue(value)
                    }
                    TLVType.PROFILE_PHOTO_HASH -> {
                        if (value.size == com.bitchat.android.model.ProfileSync.PHOTO_HASH_BYTES) {
                            profilePhotoHash = value
                        }
                    }
                    null -> {
                        // Retain unknown extensions so callers can forward or
                        // re-encode the announcement without erasing them.
                        unknownTLVs += UnknownAnnouncementTLV(typeValue.toInt(), value)
                    }
                }
            }
            
            // All three fields are required
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(
                    nickname, noisePublicKey, signingPublicKey, capabilities,
                    profileVersion, profilePhotoHash, unknownTLVs
                )
            } else {
                null
            }
        }

        /** Construct the announcement emitted by this Android build. */
        fun forLocalPeer(
            nickname: String,
            noisePublicKey: ByteArray,
            signingPublicKey: ByteArray,
            profileVersion: Long? = null,
            profilePhotoHash: ByteArray? = null
        ): IdentityAnnouncement = IdentityAnnouncement(
            nickname = nickname,
            noisePublicKey = noisePublicKey,
            signingPublicKey = signingPublicKey,
            capabilities = PeerCapabilities.LOCAL_SUPPORTED,
            profileVersion = profileVersion,
            profilePhotoHash = profilePhotoHash
        )
    }
    
    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as IdentityAnnouncement
        
        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        if (capabilities != other.capabilities) return false
        if (profileVersion != other.profileVersion) return false
        if (profilePhotoHash == null || other.profilePhotoHash == null) {
            if (profilePhotoHash != other.profilePhotoHash) return false
        } else if (!profilePhotoHash.contentEquals(other.profilePhotoHash)) return false
        if (unknownTLVs != other.unknownTLVs) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + (capabilities?.hashCode() ?: 0)
        result = 31 * result + (profileVersion?.hashCode() ?: 0)
        result = 31 * result + (profilePhotoHash?.contentHashCode() ?: 0)
        result = 31 * result + unknownTLVs.hashCode()
        return result
    }
    
    override fun toString(): String {
        return "IdentityAnnouncement(nickname='$nickname', noisePublicKey=${noisePublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., signingPublicKey=${signingPublicKey.joinToString("") { "%02x".format(it) }.take(16)}..., capabilities=${capabilities?.rawValue})"
    }
}
