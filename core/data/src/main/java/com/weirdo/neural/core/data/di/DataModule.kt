package com.weirdo.neural.core.data.di

import android.content.Context
import androidx.room.Room
import com.weirdo.neural.core.data.db.WeirdoDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): WeirdoDatabase =
        Room.databaseBuilder(ctx, WeirdoDatabase::class.java, "weirdo.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(db: WeirdoDatabase) = db.conversationDao()

    @Provides
    fun provideMessageDao(db: WeirdoDatabase) = db.messageDao()

    @Provides
    fun provideInstalledModelDao(db: WeirdoDatabase) = db.installedModelDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Sem retry por timeout de leitura — para download longo não queremos que aborte
        .retryOnConnectionFailure(true)
        .build()
}
