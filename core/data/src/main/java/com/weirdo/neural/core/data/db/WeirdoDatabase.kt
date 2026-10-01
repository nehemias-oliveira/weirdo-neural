package com.weirdo.neural.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.weirdo.neural.core.data.db.dao.ConversationDao
import com.weirdo.neural.core.data.db.dao.MessageDao
import com.weirdo.neural.core.data.db.entity.ConversationEntity
import com.weirdo.neural.core.data.db.entity.MessageEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WeirdoDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}
