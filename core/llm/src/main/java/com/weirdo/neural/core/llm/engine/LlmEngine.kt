package com.weirdo.neural.core.llm.engine

import com.weirdo.neural.core.llm.model.ChatMessage
import com.weirdo.neural.core.llm.model.LoadProgress
import com.weirdo.neural.core.llm.model.ModelConfig
import com.weirdo.neural.core.llm.model.SamplerParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LlmEngine {
    val isLoaded: StateFlow<Boolean>
    val loadedModelName: StateFlow<String?>

    fun loadModel(config: ModelConfig): Flow<LoadProgress>

    fun generate(
        messages: List<ChatMessage>,
        params: SamplerParams,
        systemPrompt: String? = null,
    ): Flow<String>

    fun countTokens(text: String): Int

    fun cancelGeneration()

    fun unload()
}
