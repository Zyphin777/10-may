package com.example.offlineroutingapp.fragments

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.ChatActivity
import com.example.offlineroutingapp.MainActivity
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.adapters.ChatListAdapter
import com.example.offlineroutingapp.data.AppDatabase
import kotlinx.coroutines.launch

class ChatsFragment : Fragment() {

    private lateinit var chatsRecyclerView: RecyclerView
    private lateinit var chatListAdapter: ChatListAdapter
    private val database by lazy { AppDatabase.getDatabase(requireContext()) }

    private val distanceRefreshHandler = Handler(Looper.getMainLooper())
    private val distanceRefreshRunnable = object : Runnable {
        override fun run() {
            if (::chatListAdapter.isInitialized) {
                chatListAdapter.notifyDataSetChanged()
            }
            distanceRefreshHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_chats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatsRecyclerView = view.findViewById(R.id.chatsRecyclerView)
        setupRecyclerView()
        loadChats()
    }

    override fun onResume() {
        super.onResume()
        distanceRefreshHandler.post(distanceRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        distanceRefreshHandler.removeCallbacks(distanceRefreshRunnable)
    }

    private fun setupRecyclerView() {
        chatListAdapter = ChatListAdapter(
            onChatClick = { chatEntity ->
                // Open chat normally (ChatActivity itself can decide routed mode if you pass extras)
                val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_CHAT_ID, chatEntity.chatId)
                    putExtra(MainActivity.EXTRA_USER_NAME, chatEntity.userName)
                    putExtra(MainActivity.EXTRA_USER_PHOTO, chatEntity.userProfilePhoto)

                    // Default: direct mode
                    putExtra(MainActivity.EXTRA_USE_ROUTED_MODE, false)
                }
                startActivity(intent)
            },
            onReconnectClick = { chatEntity ->
                /**
                 * chatEntity.chatId ممكن يكون:
                 * - MAC address (direct chat)
                 * - NodeId UUID (routed chat)
                 *
                 * لو NodeId -> مفيش reconnect WiFi Direct بين B و C، المفروض تفتحي routed chat.
                 * لو MAC -> اعملي reconnect عن طريق MainActivity.reconnectToDevice(mac)
                 */
                val id = chatEntity.chatId.orEmpty()

                val looksLikeNodeId = id.contains("-") && id.length >= 32
                if (looksLikeNodeId) {
                    // Routed: open ChatActivity in routed mode
                    val intent = Intent(requireContext(), ChatActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_CHAT_ID, id) // chatId = nodeId
                        putExtra(MainActivity.EXTRA_USER_NAME, chatEntity.userName)
                        putExtra(MainActivity.EXTRA_USER_PHOTO, chatEntity.userProfilePhoto)

                        putExtra(MainActivity.EXTRA_USE_ROUTED_MODE, true)
                        putExtra(MainActivity.EXTRA_DST_NODE_ID, id)
                    }
                    startActivity(intent)
                } else {
                    // Direct: reconnect through MainActivity
                    val main = activity as? MainActivity
                    if (main != null) {
                        main.reconnectToDevice(id)
                    } else {
                        Toast.makeText(requireContext(), "MainActivity not available", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        chatsRecyclerView.adapter = chatListAdapter
        chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun loadChats() {
        lifecycleScope.launch {
            database.chatDao().getAllChats().collect { chats ->
                chatListAdapter.submitList(chats)
            }
        }
    }
}
