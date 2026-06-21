package com.example.offlineroutingapp.adapters

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.data.entities.ChatEntity
import com.example.offlineroutingapp.location.LocationStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ChatListAdapter(
    private val onChatClick: (ChatEntity) -> Unit,
    private val onReconnectClick: (ChatEntity) -> Unit
) : ListAdapter<ChatEntity, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val profileImage: ImageView = itemView.findViewById(R.id.chatProfileImage)
        val userName: TextView      = itemView.findViewById(R.id.chatUserName)
        val distance: TextView      = itemView.findViewById(R.id.chatUserDistance)   // NEW
        val lastMessage: TextView   = itemView.findViewById(R.id.chatLastMessage)
        val timestamp: TextView     = itemView.findViewById(R.id.chatTimestamp)
        val unreadBadge: TextView   = itemView.findViewById(R.id.chatUnreadBadge)
        val reconnectBtn: Button    = itemView.findViewById(R.id.reconnectBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val chat = getItem(position)

        // ── Display name ──────────────────────────────────────────────────────
        holder.userName.text = chat.userName.ifBlank { "Unknown" }

        // ── Last message ──────────────────────────────────────────────────────
        holder.lastMessage.text = chat.lastMessage.ifEmpty { "No messages yet" }

        // ── Timestamp ─────────────────────────────────────────────────────────
        holder.timestamp.text = formatTimestamp(chat.lastMessageTime)

        // ── Profile photo ─────────────────────────────────────────────────────
        val photoPath = chat.userProfilePhoto
        if (!photoPath.isNullOrEmpty() && File(photoPath).exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(photoPath)
                holder.profileImage.setImageBitmap(bitmap)
            } catch (_: Exception) {
                holder.profileImage.setImageResource(android.R.drawable.ic_menu_camera)
            }
        } else {
            holder.profileImage.setImageResource(android.R.drawable.ic_menu_camera)
        }

        // ── Distance (NEW) ────────────────────────────────────────────────────
        // chatId is the peer's nodeId — look up their location in LocationStore
        val distanceText = LocationStore.formattedDistanceTo(chat.chatId)
        if (distanceText != null) {
            holder.distance.text       = "📍 $distanceText"
            holder.distance.visibility = View.VISIBLE
        } else {
            holder.distance.visibility = View.GONE
        }

        // ── Unread badge ──────────────────────────────────────────────────────
        if (chat.unreadCount > 0) {
            holder.unreadBadge.visibility = View.VISIBLE
            holder.unreadBadge.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
            holder.reconnectBtn.visibility = View.GONE
        } else {
            holder.unreadBadge.visibility  = View.GONE
            holder.reconnectBtn.visibility = View.VISIBLE
        }

        // ── Click listeners ───────────────────────────────────────────────────
        holder.reconnectBtn.setOnClickListener { onReconnectClick(chat) }
        holder.itemView.setOnClickListener { onChatClick(chat) }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000      -> "Just now"
            diff < 3_600_000   -> "${diff / 60_000}m ago"
            diff < 86_400_000  -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

class ChatDiffCallback : DiffUtil.ItemCallback<ChatEntity>() {
    override fun areItemsTheSame(oldItem: ChatEntity, newItem: ChatEntity) =
        oldItem.chatId == newItem.chatId
    override fun areContentsTheSame(oldItem: ChatEntity, newItem: ChatEntity) =
        oldItem == newItem
}
