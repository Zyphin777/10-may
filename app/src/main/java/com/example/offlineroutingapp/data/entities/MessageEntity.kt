package com.example.offlineroutingapp.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Chat identification
    val chatId: String,

    // Message content (text OR label like "📄 file.pdf" / "🎤 Voice Message")
    val text: String,

    // Direction
    val isSentByMe: Boolean,

    // Time
    val timestamp: Long = System.currentTimeMillis(),

    // =========================
    // Image
    // =========================
    val isImage: Boolean = false,
    val imageData: String? = null,   // Base64 (image / audio / document)

    // =========================
    // Delivery / Seen
    // =========================
    val isDelivered: Boolean = false,
    val isSeen: Boolean = false,

    // =========================
    // Voice
    // =========================
    val isAudio: Boolean = false,
    val audioDuration: Long = 0L,     // milliseconds

    // =========================
    // Document
    // =========================
    val isDocument: Boolean = false,
    val documentFileName: String? = null,

    // ✅ NEW (اختياري – مش هيكسر حاجة)
    val documentMimeType: String? = null,
    val fileSize: Long? = null,

// ── NEW: Location pin ─────────────────────────────────────────────────
/* True when this message is a GPS location share. */
val isLocation: Boolean = false,
/* Latitude of the shared location (valid when isLocation == true). */
val locationLat: Double? = null,
/* Longitude of the shared location (valid when isLocation == true). */
val locationLng: Double? = null,
/* Human-readable address / label shown below the map preview. */
val locationLabel: String? = null
)