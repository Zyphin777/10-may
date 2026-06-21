package com.example.offlineroutingapp.data.dao

import androidx.room.*
import com.example.offlineroutingapp.data.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(chatId: String): MessageEntity?

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteAllMessagesForChat(chatId: String)

    /**
     * ✅ مهم عشان نحول placeholder chatId (MAC) -> nodeId بعد ما PROFILE ييجي
     * ده بيمنع duplicate chats + بيخلي كل الداتا تتجمع في شات واحد
     */
    @Query("UPDATE messages SET chatId = :newChatId WHERE chatId = :oldChatId")
    suspend fun migrateChatId(oldChatId: String, newChatId: String)

    /**
     * ✅ اختياري (لو حبيتي كمان تنقلي الشات نفسه):
     * ارجعيلي لو عندك حالات duplicate وعايزين نمسح الـ oldChat من messages قبل delete chat row
     */
    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun countMessages(chatId: String): Int
}
