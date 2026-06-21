package com.example.offlineroutingapp.data.dao

import androidx.room.*
import com.example.offlineroutingapp.data.entities.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // REPLACE مناسب هنا عشان نفس chatId يتحدث (اسم/صورة/آخر رسالة…)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC")
    fun getAllChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE chatId = :chatId LIMIT 1")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Delete
    suspend fun deleteChat(chat: ChatEntity)

    @Query("UPDATE chats SET unreadCount = 0 WHERE chatId = :chatId")
    suspend fun markChatAsRead(chatId: String)

    // ==========================
    // ✅ New helpers (prevent missing chats / race)
    // ==========================

    /**
     * يضمن وجود الشات (لو مش موجود ينشئه).
     * لو موجود يحدث الاسم/الصورة فقط بدون ما يبوظ lastMessage.
     */
    @Transaction
    suspend fun upsertChatIdentity(
        chatId: String,
        userName: String,
        userProfilePhoto: String?
    ) {
        val existing = getChatById(chatId)
        if (existing == null) {
            insertChat(
                ChatEntity(
                    chatId = chatId,
                    userName = userName,
                    userProfilePhoto = userProfilePhoto,
                    lastMessage = "",
                    lastMessageTime = System.currentTimeMillis(),
                    unreadCount = 0
                )
            )
        } else {
            // حدث الاسم/الصورة فقط
            if (existing.userName != userName || existing.userProfilePhoto != userProfilePhoto) {
                updateChat(
                    existing.copy(
                        userName = userName,
                        userProfilePhoto = userProfilePhoto
                    )
                )
            }
        }
    }

    /**
     * تحديث آخر رسالة + الوقت + unread (مع خيار زيادة العداد).
     * ده يمنع إنك تعمل get/copy/update في كل مكان.
     */
    @Query(
        """
        UPDATE chats 
        SET lastMessage = :lastMessage,
            lastMessageTime = :lastMessageTime,
            unreadCount = CASE WHEN :incrementUnread = 1 THEN unreadCount + 1 ELSE unreadCount END
        WHERE chatId = :chatId
        """
    )
    suspend fun updateLastMessageAndUnread(
        chatId: String,
        lastMessage: String,
        lastMessageTime: Long,
        incrementUnread: Int
    )
}
