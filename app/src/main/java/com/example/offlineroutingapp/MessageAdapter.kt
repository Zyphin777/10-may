package com.example.offlineroutingapp

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    // ─────────────────────────────────────────────────────────────────────────
    // ViewHolder
    // ─────────────────────────────────────────────────────────────────────────

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageText: TextView        = itemView.findViewById(R.id.messageText)
        val messageImage: ImageView      = itemView.findViewById(R.id.messageImage)
        val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)
        val messageStatus: TextView      = itemView.findViewById(R.id.messageStatus)

        // Voice
        val voiceMessageLayout: LinearLayout = itemView.findViewById(R.id.voiceMessageLayout)
        val playVoiceBtn: ImageButton        = itemView.findViewById(R.id.playVoiceBtn)
        val voiceDurationText: TextView      = itemView.findViewById(R.id.voiceDurationText)

        // Document
        val documentMessageLayout: LinearLayout = itemView.findViewById(R.id.documentMessageLayout)
        val documentFileNameText: TextView      = itemView.findViewById(R.id.documentFileNameText)
        val documentIcon: ImageView             = itemView.findViewById(R.id.documentIcon)

        // Location (NEW)
        val locationMessageLayout: LinearLayout = itemView.findViewById(R.id.locationMessageLayout)
        val locationMapThumbnail: ImageView      = itemView.findViewById(R.id.locationMapThumbnail)
        val locationLabelText: TextView          = itemView.findViewById(R.id.locationLabelText)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapter overrides
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        // Reset all views
        holder.messageText.visibility           = View.GONE
        holder.messageImage.visibility          = View.GONE
        holder.voiceMessageLayout.visibility    = View.GONE
        holder.documentMessageLayout.visibility = View.GONE
        holder.locationMessageLayout.visibility = View.GONE  // NEW

        // Alignment
        val gravity  = if (message.isSentByMe) Gravity.END else Gravity.START
        val bubbleRes = if (message.isSentByMe) R.drawable.bg_message_sent
        else                    R.drawable.bg_message_received
        holder.messageContainer.gravity = gravity

        val textColor = if (message.isSentByMe)
            context.getColor(android.R.color.white)
        else
            context.getColor(android.R.color.black)

        when {// ─── IMAGE ───────────────────────────────────────────────────────
            message.isImage && !message.imageData.isNullOrEmpty() && !message.isAudio && !message.isDocument -> {
                holder.messageImage.visibility = View.VISIBLE
                holder.messageImage.setBackgroundResource(bubbleRes)
                try {
                    val bytes  = Base64.decode(message.imageData, Base64.NO_WRAP)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    holder.messageImage.setImageBitmap(bitmap)
                    holder.messageImage.setOnClickListener { showFullscreenImage(context, bytes) }
                } catch (_: Exception) {
                    holder.messageImage.setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }

            // ─── VOICE ───────────────────────────────────────────────────────
            message.isAudio && !message.imageData.isNullOrEmpty() -> {
                holder.voiceMessageLayout.visibility = View.VISIBLE
                holder.voiceMessageLayout.setBackgroundResource(bubbleRes)
                holder.playVoiceBtn.setColorFilter(textColor)
                holder.voiceDurationText.setTextColor(textColor)
                holder.voiceDurationText.text = formatDuration(message.audioDuration)
                holder.playVoiceBtn.setImageResource(android.R.drawable.ic_media_play)
                holder.playVoiceBtn.tag = "paused"
                holder.playVoiceBtn.setOnClickListener {
                    toggleAudioPlayback(context, message.imageData, holder.playVoiceBtn)
                }
            }

            // ─── DOCUMENT ────────────────────────────────────────────────────
            message.isDocument && !message.imageData.isNullOrEmpty() && !message.documentFileName.isNullOrEmpty() -> {
                holder.documentMessageLayout.visibility = View.VISIBLE
                holder.documentMessageLayout.setBackgroundResource(bubbleRes)
                holder.documentFileNameText.text = message.documentFileName
                holder.documentFileNameText.setTextColor(textColor)
                holder.documentIcon.setColorFilter(textColor)
                holder.documentMessageLayout.setOnClickListener {
                    openDocument(context, message.imageData, message.documentFileName)
                }
            }

            // ─── LOCATION (NEW) ──────────────────────────────────────────────
            message.isLocation && message.locationLat != null && message.locationLng != null -> {
                holder.locationMessageLayout.visibility = View.VISIBLE

                // Label
                holder.locationLabelText.text = message.locationLabel
                    ?.takeIf { it.isNotBlank() } ?: "Shared Location"

                // Static map thumbnail via Google Maps Static API
                loadStaticMapThumbnail(
                    context   = context,
                    imageView = holder.locationMapThumbnail,
                    lat       = message.locationLat,
                    lng       = message.locationLng
                )

                // Tap → open Google Maps at pin
                holder.locationMessageLayout.setOnClickListener {
                    openInMaps(context, message.locationLat, message.locationLng, message.locationLabel)
                }
            }

            // ─── TEXT ────────────────────────────────────────────────────────
            else -> {
                holder.messageText.visibility = View.VISIBLE
                holder.messageText.text = message.text
                holder.messageText.setBackgroundResource(bubbleRes)
                holder.messageText.setTextColor(textColor)
            }
        }// Status ticks
        if (message.isSentByMe) {
            holder.messageStatus.visibility = View.VISIBLE
            holder.messageStatus.text = when {
                message.isSeen      -> "✓✓ Seen"
                message.isDelivered -> "✓✓ Delivered"
                else                -> "✓ Sent"
            }
            holder.messageStatus.setTextColor(context.getColor(android.R.color.darker_gray))
        } else {
            holder.messageStatus.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = messages.size

    // ─────────────────────────────────────────────────────────────────────────
    // Location helpers (NEW)
    // ─────────────────────────────────────────────────────────────────────────

    /* Load a 240×130 dp static map tile centred on [lat]/[lng] with a red pin.
    *
    * Uses the Maps Static API.  If the API key is absent or the load fails,
    * the placeholder map icon remains visible.
    *
    * To enable: add your Maps Static API key to res/values/strings.xml as
    *   <string name="maps_static_api_key">YOUR_KEY_HERE</string>
    * and un-comment the key parameter in the URL below.
    *
    * NOTE: The Maps Static API is optional.  Without a key the thumbnail
    * shows a grey placeholder; tapping still opens Google Maps correctly.
    */
    private fun loadStaticMapThumbnail(
        context: Context,
        imageView: ImageView,
        lat: Double,
        lng: Double
    ) {
        val widthPx  = (240 * context.resources.displayMetrics.density).toInt()
        val heightPx = (130 * context.resources.displayMetrics.density).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background gradient
        val gradient = android.graphics.LinearGradient(
            0f, 0f, 0f, heightPx.toFloat(),
            Color.parseColor("#E8F4F8"),
            Color.parseColor("#C8DFE8"),
            android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
        paint.shader = null

        // Grid lines to suggest a map
        paint.color       = Color.parseColor("#B0CDD8")
        paint.strokeWidth = 1f
        paint.style       = Paint.Style.STROKE
        val gridSpacing   = 40f * context.resources.displayMetrics.density
        var x = 0f
        while (x < widthPx) {
            canvas.drawLine(x, 0f, x, heightPx.toFloat(), paint)
            x += gridSpacing
        }
        var y = 0f
        while (y < heightPx) {
            canvas.drawLine(0f, y, widthPx.toFloat(), y, paint)
            y += gridSpacing
        }

        // Pin shadow
        val cx = widthPx / 2f
        val cy = heightPx / 2f
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#40000000")
        canvas.drawCircle(cx + 3f, cy + 3f, 22f, paint)

        // Pin circle
        paint.color = Color.parseColor("#673AB7")
        canvas.drawCircle(cx, cy, 22f, paint)

        // Pin white dot
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 10f, paint)

        // Coordinates text
        paint.textSize  = 11f * context.resources.displayMetrics.density
        paint.textAlign = Paint.Align.CENTER
        paint.color     = Color.parseColor("#455A64")
        canvas.drawText(
            "${"%.4f".format(lat)}, ${"%.4f".format(lng)}",
            widthPx / 2f,
            heightPx - 10f * context.resources.displayMetrics.density,
            paint
        )

        imageView.setImageBitmap(bitmap)
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /* Open Google Maps (or a browser geo: URI fallback) at the given coordinates.
    */
    private fun openInMaps(context: Context, lat: Double, lng: Double, label: String?) {
        val encodedLabel = Uri.encode(label ?: "Shared Location")
        val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($encodedLabel)")
        val intent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            // Fallback: browser
            val browserUri = Uri.parse("https://maps.google.com/?q=$lat,$lng")
            context.startActivity(Intent(Intent.ACTION_VIEW, browserUri))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Existing helpers (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private fun showFullscreenImage(context: Context, imageBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        val imageView = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        AlertDialog.Builder(context)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openDocument(context: Context, base64: String, fileName: String) {
        try {
            val bytes   = Base64.decode(base64, Base64.NO_WRAP)
            val tempDir = File(context.cacheDir, "documents").also { it.mkdirs() }
            val tempFile = File(tempDir, fileName)
            FileOutputStream(tempFile).use { it.write(bytes) }
            val ext  = MimeTypeMap.getFileExtensionFromUrl(fileName)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
            val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)

            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            Toast.makeText(context, "Can't open document", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleAudioPlayback(context: Context, audioBase64: String, playButton: ImageButton) {
        if (isPlaying && playButton.tag == "playing") {
            stopPlayback()
            playButton.setImageResource(android.R.drawable.ic_media_play)
            playButton.tag = "paused"
        } else {
            stopPlayback()
            startPlayback(context, audioBase64, playButton)
        }
    }

    private fun startPlayback(context: Context, audioBase64: String, playButton: ImageButton) {
        try {
            val audioBytes = Base64.decode(audioBase64, Base64.NO_WRAP)
            val tempFile   = File(context.cacheDir, "temp_voice_${System.currentTimeMillis()}.3gp")
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                    playButton.setImageResource(android.R.drawable.ic_media_play)
                    playButton.tag = "paused"
                    try { tempFile.delete() } catch (_: Exception) {}
                }
                start()
            }
            isPlaying = true
            playButton.setImageResource(android.R.drawable.ic_media_pause)
            playButton.tag = "playing"
        } catch (_: Exception) {
            stopPlayback()
            playButton.setImageResource(android.R.drawable.ic_media_play)
            playButton.tag = "paused"
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        mediaPlayer = null
        isPlaying   = false
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "0:00"
        val totalSeconds = durationMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    fun releaseMediaPlayer() { stopPlayback() }
}