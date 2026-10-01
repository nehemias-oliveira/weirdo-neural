package com.weirdo.neural.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.weirdo.neural.core.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt ASC")
    fun observeForConversation(cid: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long
}
