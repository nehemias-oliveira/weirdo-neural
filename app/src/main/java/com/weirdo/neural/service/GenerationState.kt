package com.weirdo.neural.service

sealed interface GenerationState {

    data object Idle : GenerationState

    /**
     * Geração em andamento. O buffer é o texto acumulado até agora
     * (pode conter o bloco <think> completo).
     */
    data class Running(
        val buffer: String,
        val tokensGenerated: Int,
        val startedAt: Long,
        val modelId: String?,
    ) : GenerationState

    data class Completed(
        val finalText: String,
        val tokensGenerated: Int,
        val durationMs: Long,
        val modelId: String?,
    ) : GenerationState

    data class Failed(
        val message: String,
        val partialText: String?,
    ) : GenerationState

    data object Cancelled : GenerationState
}
