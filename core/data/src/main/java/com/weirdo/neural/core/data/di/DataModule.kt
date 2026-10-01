package com.weirdo.neural.core.data.di

import android.content.Context
import androidx.room.Room
import com.weirdo.neural.core.data.db.WeirdoDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): WeirdoDatabase =
        Room.databaseBuilder(ctx, WeirdoDatabase::class.java, "weirdo.db").build()

    @Provides
    fun provideConversationDao(db: WeirdoDatabase) = db.conversationDao()

    @Provides
    fun provideMessageDao(db: WeirdoDatabase) = db.messageDao()
}
