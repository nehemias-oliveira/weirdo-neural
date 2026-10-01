package com.weirdo.neural.core.llm.llamacpp.di

import com.weirdo.neural.core.llm.engine.LlmEngine
import com.weirdo.neural.core.llm.llamacpp.LlamaCppEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {

    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: LlamaCppEngine): LlmEngine
}
