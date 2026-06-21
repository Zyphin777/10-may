package com.example.offlineroutingapp.offload

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * OffloadRepository
 *
 * Persists the list of offloaded media entries using SharedPreferences + Gson.
 * This allows the app to:
 *   – Know which messages have already been saved (avoid duplicates).
 *   – Show a history of saved files to the user.
 *
 * Each entry stores the message DB id, the local Uri, media type, and timestamp.
 */
object OffloadRepository {

    private const val TAG = "OffloadRepository"
    private const val PREFS_NAME = "offload_prefs"
    private const val KEY_ENTRIES = "offload_entries"

    private val gson = Gson()

    // ─────────────────────────────────────────────────────────────────────────
    // Data model
    // ─────────────────────────────────────────────────────────────────────────

    enum class MediaType { IMAGE, FILE, AUDIO }

    /**
     * Represents a single saved media item.
     *
     * @param messageId   The Room [MessageEntity.id] of the originating message.
     * @param localUri    The Uri where the file was written on the device.
     * @param mediaType   IMAGE, FILE, or AUDIO.
     * @param fileName    Human-readable file name (for display purposes).
     * @param direction   "sent" or "received".
     * @param savedAt     Epoch millis when the file was offloaded.
     * @param chatId      The chatId the message belongs to.
     */
    data class OffloadEntry(
        val messageId: Long,
        val localUri: String,          // Uri serialised as String
        val mediaType: MediaType,
        val fileName: String,
        val direction: String,
        val savedAt: Long,
        val chatId: String
    )

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    /** Add a new offload entry and persist it. */
    fun addEntry(context: Context, entry: OffloadEntry) {
        val entries = loadAll(context).toMutableList()
        // Deduplicate by messageId
        entries.removeAll { it.messageId == entry.messageId }
        entries.add(entry)
        save(context, entries)
        Log.d(TAG, "Offload entry added: msgId=${entry.messageId} uri=${entry.localUri}")
    }

    /** Returns true if this messageId has already been offloaded. */
    fun isOffloaded(context: Context, messageId: Long): Boolean =
        loadAll(context).any { it.messageId == messageId }

    /** Retrieve all offload entries, newest first. */
    fun loadAll(context: Context): List<OffloadEntry> {
        val prefs = prefs(context)
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<OffloadEntry>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize offload entries: ${e.message}")
            emptyList()
        }
    }

    /** Retrieve entries filtered by chat. */
    fun loadByChatId(context: Context, chatId: String): List<OffloadEntry> =
        loadAll(context).filter { it.chatId == chatId }

    /** Remove a specific entry (e.g. if the local file was deleted). */
    fun removeEntry(context: Context, messageId: Long) {
        val entries = loadAll(context).toMutableList()
        entries.removeAll { it.messageId == messageId }
        save(context, entries)
        Log.d(TAG, "Offload entry removed: msgId=$messageId")
    }

    /** Wipe all stored offload metadata. */
    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
        Log.d(TAG, "All offload entries cleared")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun save(context: Context, entries: List<OffloadEntry>) {
        prefs(context).edit().putString(KEY_ENTRIES, gson.toJson(entries)).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
