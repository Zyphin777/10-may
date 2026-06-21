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
import com.example.offlineroutingapp.fragments.PublicRequestUiModel

class PublicRequestAdapter(
    private val onHaveItClick: (PublicRequestUiModel) -> Unit,
    private val onDownloadClick: (PublicRequestUiModel) -> Unit,
    private val onOpenFileClick: (PublicRequestUiModel) -> Unit
) : ListAdapter<PublicRequestUiModel, PublicRequestAdapter.PublicRequestViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PublicRequestViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_public_request, parent, false)
        return PublicRequestViewHolder(view)
    }

    override fun onBindViewHolder(holder: PublicRequestViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PublicRequestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val requesterNameText: TextView = itemView.findViewById(R.id.requesterNameText)
        private val requestTimeText: TextView = itemView.findViewById(R.id.requestTimeText)
        private val requestBodyText: TextView = itemView.findViewById(R.id.requestBodyText)
        private val requestStatusText: TextView = itemView.findViewById(R.id.requestStatusText)
        private val haveItBtn: Button = itemView.findViewById(R.id.haveItBtn)
        private val selectedFileContainer: View = itemView.findViewById(R.id.selectedFileContainer)
        private val selectedFileNameText: TextView = itemView.findViewById(R.id.selectedFileNameText)
        private val selectedFileSizeText: TextView = itemView.findViewById(R.id.selectedFileSizeText)

        fun bind(request: PublicRequestUiModel) {
            requesterNameText.text = if (request.isMine) "Me (${request.requesterName})" else request.requesterName
            requestTimeText.text = request.timeText
            requestBodyText.text = request.requestText
            requestStatusText.text = request.status

            if (request.offeredFileName != null) {
                selectedFileContainer.visibility = View.VISIBLE
                selectedFileNameText.text = request.offeredFileName
                selectedFileSizeText.text = request.offeredFileSizeText ?: "Unknown size"
            } else {
                selectedFileContainer.visibility = View.GONE
            }

            when {
                request.downloadedFilePath != null -> {
                    haveItBtn.visibility = View.VISIBLE
                    haveItBtn.text = "Open File"
                    haveItBtn.setOnClickListener { onOpenFileClick(request) }
                }

                request.canDownload -> {
                    haveItBtn.visibility = View.VISIBLE
                    haveItBtn.text = "Download"
                    haveItBtn.setOnClickListener { onDownloadClick(request) }
                }

                request.isMine -> {
                    haveItBtn.visibility = View.GONE
                    haveItBtn.setOnClickListener(null)
                }

                request.offeredFileName != null -> {
                    haveItBtn.visibility = View.VISIBLE
                    haveItBtn.text = "Change File"
                    haveItBtn.setOnClickListener { onHaveItClick(request) }
                }

                else -> {
                    haveItBtn.visibility = View.VISIBLE
                    haveItBtn.text = "I Have It"
                    haveItBtn.setOnClickListener { onHaveItClick(request) }
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<PublicRequestUiModel>() {
        override fun areItemsTheSame(
            oldItem: PublicRequestUiModel,
            newItem: PublicRequestUiModel
        ): Boolean {
            return oldItem.requestId == newItem.requestId
        }

        override fun areContentsTheSame(
            oldItem: PublicRequestUiModel,
            newItem: PublicRequestUiModel
        ): Boolean {
            return oldItem == newItem
        }
    }
}
