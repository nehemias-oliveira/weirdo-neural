package com.weirdo.neural.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.weirdo.neural.core.data.db.dao.ConversationDao
import com.weirdo.neural.core.data.db.dao.InstalledModelDao
import com.weirdo.neural.core.data.db.dao.MessageDao
import com.weirdo.neural.core.data.db.entity.ConversationEntity
import com.weirdo.neural.core.data.db.entity.InstalledModelEntity
import com.weirdo.neural.core.data.db.entity.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        InstalledModelEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class WeirdoDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun installedModelDao(): InstalledModelDao
}
