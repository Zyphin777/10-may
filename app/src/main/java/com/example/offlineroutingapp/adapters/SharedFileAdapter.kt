package com.example.offlineroutingapp.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.offloading.CachedSharedFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedFileAdapter(
    private val onOpenClick: (CachedSharedFile) -> Unit,
    private val onDeleteClick: (CachedSharedFile) -> Unit
) : ListAdapter<CachedSharedFile, SharedFileAdapter.SharedFileViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SharedFileViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shared_file, parent, false)
        return SharedFileViewHolder(view)
    }

    override fun onBindViewHolder(holder: SharedFileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SharedFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileNameText: TextView = itemView.findViewById(R.id.sharedFileNameText)
        private val fileMetaText: TextView = itemView.findViewById(R.id.sharedFileMetaText)
        private val fileHashText: TextView = itemView.findViewById(R.id.sharedFileHashText)
        private val fileTagsText: TextView = itemView.findViewById(R.id.sharedFileTagsText)
        private val openButton: Button = itemView.findViewById(R.id.openSharedFileBtn)
        private val deleteButton: Button = itemView.findViewById(R.id.deleteSharedFileBtn)

        fun bind(file: CachedSharedFile) {
            fileNameText.text = file.fileName
            fileMetaText.text = buildString {
                append(file.fileSizeText)
                append(" • ")
                append(file.category)
                append(" • ")
                append(file.sourceType)
                if (file.cachedAt > 0L) {
                    append(" • ")
                    append(SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(file.cachedAt)))
                }
            }

            val hashPreview = file.fileHash?.take(16)
            fileHashText.text = if (hashPreview.isNullOrBlank()) {
                "SHA-256: not available"
            } else {
                "SHA-256: $hashPreview..."
            }

            val tagsText = listOf(file.description, file.tags)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            fileTagsText.text = tagsText.ifBlank { "No tags yet" }

            openButton.setOnClickListener { onOpenClick(file) }
            deleteButton.setOnClickListener { onDeleteClick(file) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<CachedSharedFile>() {
        override fun areItemsTheSame(oldItem: CachedSharedFile, newItem: CachedSharedFile): Boolean {
            return oldItem.fileId == newItem.fileId
        }

        override fun areContentsTheSame(oldItem: CachedSharedFile, newItem: CachedSharedFile): Boolean {
            return oldItem == newItem
        }
    }
}
