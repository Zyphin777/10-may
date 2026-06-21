package com.example.offlineroutingapp.offloading

data class CachedSharedFile(
    val fileId: String = "",
    val fileName: String = "",
    val fileSizeBytes: Long = -1L,
    val fileSizeText: String = "Unknown size",
    val localUri: String = "",
    val sourceType: String = "DOWNLOADED",
    val cachedAt: Long = 0L,
    val fileHash: String? = null,
    val category: String = "General",
    val description: String = "",
    val tags: String = "",
    val lastSharedAt: Long? = null
)
