package com.example.offlineroutingapp.offloading

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import java.io.File

object SharedFileCacheStore {
    private const val TAG = "SharedFileCacheStore"
    private const val SHARED_FILES_PREFS = "cached_shared_files_cache"
    private const val SHARED_FILES_CACHE_KEY = "cached_files_json"

    // Limit only downloaded/cached files that are stored inside this app.
    // User-selected LOCAL content URIs are just references, so they are not deleted.
    const val MAX_CACHE_SIZE_BYTES: Long = 500L * 1024L * 1024L

    private val gson = Gson()

    fun load(context: Context): MutableList<CachedSharedFile> {
        val prefs = context.getSharedPreferences(SHARED_FILES_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(SHARED_FILES_CACHE_KEY, null) ?: return mutableListOf()

        return runCatching {
            gson.fromJson(json, Array<CachedSharedFile>::class.java)
                .mapNotNull { sanitize(it) }
                .toMutableList()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to load cached shared files: ${error.message}")
            mutableListOf()
        }
    }

    private fun sanitize(file: CachedSharedFile?): CachedSharedFile? {
        if (file == null) return null
        val safeFileName = safeString(file.fileName, "")
        val safeLocalUri = safeString(file.localUri, "")
        if (safeFileName.isBlank() || safeLocalUri.isBlank()) return null
        return CachedSharedFile(
            fileId = safeString(file.fileId, "${safeFileName}_${file.fileSizeBytes}"),
            fileName = safeFileName,
            fileSizeBytes = file.fileSizeBytes,
            fileSizeText = safeString(file.fileSizeText, "Unknown size"),
            localUri = safeLocalUri,
            sourceType = safeString(file.sourceType, "DOWNLOADED"),
            cachedAt = file.cachedAt,
            fileHash = file.fileHash,
            category = safeString(file.category, "General"),
            description = safeString(file.description, ""),
            tags = safeString(file.tags, ""),
            lastSharedAt = file.lastSharedAt
        )
    }

    private fun safeString(value: String?, fallback: String): String {
        return if (value.isNullOrBlank()) fallback else value
    }

    fun save(context: Context, files: List<CachedSharedFile>) {
        context.getSharedPreferences(SHARED_FILES_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SHARED_FILES_CACHE_KEY, gson.toJson(files))
            .apply()
    }

    fun upsertAndPrune(context: Context, newFile: CachedSharedFile): MutableList<CachedSharedFile> {
        val files = load(context)
        val existingIndex = files.indexOfFirst { oldFile ->
            val sameHash = !oldFile.fileHash.isNullOrBlank() && oldFile.fileHash == newFile.fileHash
            val sameNameAndSize = oldFile.fileName.equals(newFile.fileName, ignoreCase = true) &&
                oldFile.fileSizeBytes == newFile.fileSizeBytes
            sameHash || sameNameAndSize || oldFile.fileId == newFile.fileId
        }

        if (existingIndex != -1) {
            files[existingIndex] = newFile
        } else {
            files.add(0, newFile)
        }

        pruneDownloadedCache(context, files)
        save(context, files)
        return files
    }

    fun remove(context: Context, fileId: String): MutableList<CachedSharedFile> {
        val files = load(context)
        val removed = files.firstOrNull { it.fileId == fileId }
        files.removeAll { it.fileId == fileId }
        removed?.let { deleteLocalFileIfSafe(context, it) }
        save(context, files)
        return files
    }

    private fun pruneDownloadedCache(context: Context, files: MutableList<CachedSharedFile>) {
        var downloadedSize = files
            .filter { it.sourceType != "LOCAL" }
            .sumOf { it.fileSizeBytes.coerceAtLeast(0L) }

        if (downloadedSize <= MAX_CACHE_SIZE_BYTES) return

        val removable = files
            .filter { it.sourceType != "LOCAL" }
            .sortedBy { it.cachedAt }

        for (file in removable) {
            if (downloadedSize <= MAX_CACHE_SIZE_BYTES) break
            files.removeAll { it.fileId == file.fileId }
            downloadedSize -= file.fileSizeBytes.coerceAtLeast(0L)
            deleteLocalFileIfSafe(context, file)
            Log.d(TAG, "Pruned cached file because cache limit was exceeded: ${file.fileName}")
        }
    }

    private fun deleteLocalFileIfSafe(context: Context, cachedFile: CachedSharedFile) {
        runCatching {
            val uri = Uri.parse(cachedFile.localUri)
            if (uri.scheme != "file") return
            val path = uri.path ?: return
            val file = File(path)
            val downloadsDir = File(context.filesDir, "offline_downloads")
            if (file.exists() && file.absolutePath.startsWith(downloadsDir.absolutePath)) {
                file.delete()
            }
        }.onFailure { error ->
            Log.w(TAG, "Could not delete cached file ${cachedFile.fileName}: ${error.message}")
        }
    }
}
