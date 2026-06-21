package com.example.offlineroutingapp.offload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaOffloadManager
 *
 * Handles saving sent and received images/files to the device's local storage.
 *
 * – Images  → saved to Pictures/OfflineChat  (MediaStore on API 29+, direct on older)
 * – Files   → saved to Downloads/OfflineChat (MediaStore on API 29+, direct on older)
 * – Audio   → saved to Music/OfflineChat     (MediaStore on API 29+, direct on older)
 *
 * All public functions are suspend functions and run on Dispatchers.IO internally.
 */
object MediaOffloadManager {

    private const val TAG = "MediaOffloadManager"
    private const val APP_FOLDER = "OfflineChat"

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Save an image (as raw bytes) to the device gallery.
     *
     * @param context     Application context.
     * @param imageBytes  Raw JPEG/PNG bytes.
     * @param direction   "sent" or "received" — used to build a descriptive filename.
     * @return            The saved file's [Uri] on success, null on failure.
     */
    suspend fun saveImage(
        context: Context,
        imageBytes: ByteArray,
        direction: String = "received"
    ): Uri? = withContext(Dispatchers.IO) {
        val fileName = buildFileName("IMG", direction, "jpg")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveImageViaMediaStore(context, imageBytes, fileName)
            } else {
                saveImageLegacy(context, imageBytes, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveImage failed: ${e.message}", e)
            null
        }
    }

    /**
     * Save a file (as raw bytes) to the device Downloads folder.
     *
     * @param context    Application context.
     * @param fileBytes  Raw file bytes.
     * @param fileName   Original file name including extension (e.g. "report.pdf").
     * @param mimeType   MIME type (e.g. "application/pdf").
     * @param direction  "sent" or "received".
     * @return           The saved file's [Uri] on success, null on failure.
     */
    suspend fun saveFile(
        context: Context,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String = "*/*",
        direction: String = "received"
    ): Uri? = withContext(Dispatchers.IO) {
        val safeFileName = buildFileName("FILE", direction, extensionOf(fileName), fileName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveFileViaMediaStore(context, fileBytes, safeFileName, mimeType)
            } else {
                saveFileLegacy(context, fileBytes, safeFileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveFile failed: ${e.message}", e)
            null
        }
    }

    /**
     * Save a voice/audio message (as raw bytes) to the device Music folder.
     *
     * @param context    Application context.
     * @param audioBytes Raw audio bytes (3gp / aac / etc.).
     * @param extension  File extension without the leading dot, default "3gp".
     * @param direction  "sent" or "received".
     * @return           The saved file's [Uri] on success, null on failure.
     */
    suspend fun saveAudio(
        context: Context,
        audioBytes: ByteArray,
        extension: String = "3gp",
        direction: String = "received"
    ): Uri? = withContext(Dispatchers.IO) {
        val fileName = buildFileName("AUDIO", direction, extension)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveAudioViaMediaStore(context, audioBytes, fileName, extension)
            } else {
                saveAudioLegacy(context, audioBytes, fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "saveAudio failed: ${e.message}", e)
            null
        }
    }

    /**
     * Convenience overload: decode a Base64 string and save as an image.
     */
    suspend fun saveImageFromBase64(
        context: Context,
        base64: String,
        direction: String = "received"
    ): Uri? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            saveImage(context, bytes, direction)
        } catch (e: Exception) {
            Log.e(TAG, "saveImageFromBase64 failed: ${e.message}", e)
            null
        }
    }

    /**
     * Convenience overload: decode a Base64 string and save as a file.
     */
    suspend fun saveFileFromBase64(
        context: Context,
        base64: String,
        fileName: String,
        mimeType: String = "*/*",
        direction: String = "received"
    ): Uri? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            saveFile(context, bytes, fileName, mimeType, direction)
        } catch (e: Exception) {
            Log.e(TAG, "saveFileFromBase64 failed: ${e.message}", e)
            null
        }
    }

    /**
     * Convenience overload: decode a Base64 string and save as audio.
     */
    suspend fun saveAudioFromBase64(
        context: Context,
        base64: String,
        extension: String = "3gp",
        direction: String = "received"
    ): Uri? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            saveAudio(context, bytes, extension, direction)
        } catch (e: Exception) {
            Log.e(TAG, "saveAudioFromBase64 failed: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaStore helpers (API 29+)
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveImageViaMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$APP_FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        Log.d(TAG, "Image saved to MediaStore: $uri")
        return uri
    }

    // @RequiresApi is required because every symbol in MediaStore.Downloads
    // (DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH, IS_PENDING, getContentUri) was
    // added in API 29.  The call-site in saveFile() already guards with
    // Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, so this is never reached
    // on older devices.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveFileViaMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$APP_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        // getContentUri(VOLUME_EXTERNAL_PRIMARY) is the correct API-29 call;
        // no fallback branch needed — this function is only called on API 29+.
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        Log.d(TAG, "File saved to MediaStore Downloads: $uri")
        return uri
    }

    private fun saveAudioViaMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        extension: String
    ): Uri? {
        val mime = when (extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            else  -> "audio/3gpp"
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$APP_FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        resolver.openOutputStream(uri)?.use { it.write(bytes) }

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        Log.d(TAG, "Audio saved to MediaStore: $uri")
        return uri
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy helpers (API < 29)
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveImageLegacy(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            APP_FOLDER
        )
        return writeLegacyFile(bytes, dir, fileName)
    }

    private fun saveFileLegacy(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APP_FOLDER
        )
        return writeLegacyFile(bytes, dir, fileName)
    }

    private fun saveAudioLegacy(context: Context, bytes: ByteArray, fileName: String): Uri? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            APP_FOLDER
        )
        return writeLegacyFile(bytes, dir, fileName)
    }

    private fun writeLegacyFile(bytes: ByteArray, dir: File, fileName: String): Uri? {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create directory: $dir")
            return null
        }
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        Log.d(TAG, "File saved (legacy): ${file.absolutePath}")
        return Uri.fromFile(file)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filename helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a unique, descriptive filename.
     *
     * Pattern: {prefix}_{direction}_{timestamp}.{ext}
     * e.g.    IMG_received_20250408_143022.jpg
     *
     * If [originalName] is provided (for files), extract its base name and include it.
     * e.g.    FILE_received_report_20250408_143022.pdf
     */
    private fun buildFileName(
        prefix: String,
        direction: String,
        ext: String,
        originalName: String? = null
    ): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = if (originalName != null) {
            val stripped = originalName
                .substringBeforeLast(".")
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .take(40)               // cap to avoid filesystem limits
            "${prefix}_${direction}_${stripped}_$ts"
        } else {
            "${prefix}_${direction}_$ts"
        }
        return "$base.$ext"
    }

    /** Extract the file extension from a filename, defaulting to "bin". */
    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "bin").lowercase()
}
