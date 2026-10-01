package com.weirdo.neural.core.llm.model

data class SamplerParams(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val maxTokens: Int = 512,
    val seed: Int = -1,
)
