package com.weirdo.neural.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.weirdo.neural.core.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(cid: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC, id ASC")
    suspend fun listForConversation(cid: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :cid")
    suspend fun countForConversation(cid: Long): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :cid")
    suspend fun deleteAllForConversation(cid: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
