package com.example.offlineroutingapp.offloading

object OfflinePacketTypes {
    const val PUBLIC_REQUEST = "PUBLIC_REQUEST"
    const val FILE_OFFER = "FILE_OFFER"
    const val FILE_REQUEST = "FILE_REQUEST"
    const val FILE_CHUNK = "FILE_CHUNK"
    const val FILE_ACK = "FILE_ACK"
}

data class NetworkPacket(
    val type: String,
    val packetId: String,
    val senderNodeId: String,
    val receiverNodeId: String?,
    val payload: String,
    val timestamp: Long
)


data class PublicRequestPayload(
    val requestId: String,
    val requesterNodeId: String,
    val requesterName: String,
    val requestText: String,
    val timestamp: Long,
    val ttl: Long,
    val hopCount: Int,
    val maxHopCount: Int,
    val status: String
)

data class FileOfferPayload(
    val offerId: String,
    val requestId: String,
    val ownerNodeId: String,
    val ownerName: String,
    val requesterNodeId: String?,
    val fileId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileSizeText: String,
    val timestamp: Long,
    val fileHash: String? = null,
    val category: String = "General",
    val tags: String = ""
)

data class PendingFileOffer(
    val offerId: String,
    val requestId: String,
    val fileId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileSizeText: String,
    val localFileUri: String,
    val packetJson: String,
    val createdAt: Long,
    val fileHash: String? = null,
    val category: String = "General",
    val tags: String = ""
)

data class FileRequestPayload(
    val requestId: String,
    val offerId: String,
    val fileId: String,
    val requesterNodeId: String,
    val ownerNodeId: String,
    val timestamp: Long
)

data class FileChunkPayload(
    val requestId: String,
    val offerId: String,
    val fileId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val chunkIndex: Int,
    val totalChunks: Int,
    val base64Data: String,
    val timestamp: Long,
    val fileHash: String? = null,
    val category: String = "General",
    val tags: String = ""
)


data class FileAckPayload(
    val requestId: String,
    val offerId: String,
    val fileId: String,
    val fileName: String,
    val receiverNodeId: String,
    val ownerNodeId: String,
    val savedPath: String?,
    val timestamp: Long
)
