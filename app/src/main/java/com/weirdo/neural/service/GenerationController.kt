package com.weirdo.neural.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.weirdo.neural.core.llm.engine.LlmEngine
import com.weirdo.neural.core.llm.model.ChatMessage
import com.weirdo.neural.core.llm.model.SamplerParams
import com.weirdo.neural.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orquestra uma geração que sobrevive a mudanças de tela.
 *
 * Vive enquanto o processo existir. A ViewModel observa [state] e
 * manda start/cancel. O [InferenceService] mantém o processo vivo
 * enquanto houver geração em andamento.
 */
@Singleton
class GenerationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LlmEngine,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "GenerationController"
        private const val EMIT_THROTTLE_MS = 60L
    }

    private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val state: StateFlow<GenerationState> = _state.asStateFlow()

    private var generationJob: Job? = null

    /** Inicia uma geração. Se já houver uma em andamento, ignora. */
    fun start(
        history: List<ChatMessage>,
        params: SamplerParams,
        modelId: String?,
    ) {
        if (_state.value is GenerationState.Running) {
            Log.w(TAG, "Já existe uma geração em andamento")
            return
        }

        if (!engine.isLoaded.value) {
            _state.value = GenerationState.Failed("Nenhum modelo carregado", null)
            return
        }

        // Sobe o foreground service para segurar o processo
        InferenceService.start(context)

        val startMs = System.currentTimeMillis()
        _state.value = GenerationState.Running(
            buffer = "",
            tokensGenerated = 0,
            startedAt = startMs,
            modelId = modelId,
        )

        generationJob = appScope.launch {
            val buffer = StringBuilder()
            var tokenCount = 0
            var lastEmitMs = 0L

            try {
                engine.generate(
                    messages = history,
                    params = params,
                    systemPrompt = null,
                ).collect { token ->
                    buffer.append(token)
                    tokenCount++
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= EMIT_THROTTLE_MS) {
                        lastEmitMs = now
                        _state.value = GenerationState.Running(
                            buffer = buffer.toString(),
                            tokensGenerated = tokenCount,
                            startedAt = startMs,
                            modelId = modelId,
                        )
                    }
                }

                // Emit final
                val duration = System.currentTimeMillis() - startMs
                _state.value = GenerationState.Completed(
                    finalText = buffer.toString(),
                    tokensGenerated = tokenCount,
                    durationMs = duration,
                    modelId = modelId,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Erro na geração", t)
                val partial = buffer.toString().takeIf { it.isNotBlank() }
                _state.value = GenerationState.Failed(
                    message = t.message ?: "Erro desconhecido",
                    partialText = partial,
                )
            } finally {
                generationJob = null
                InferenceService.stop(context)
            }
        }
    }

    fun cancel() {
        engine.cancelGeneration()
        generationJob?.cancel()
        generationJob = null
        _state.value = GenerationState.Cancelled
        InferenceService.stop(context)
    }

    /**
     * Chamado pela ViewModel quando ela (re)aparece e já existe uma
     * geração em andamento. Retorna o estado atual sem iniciar nada.
     */
    fun currentState(): GenerationState = _state.value

    /**
     * Marca o estado como Idle. Chamado depois que a ViewModel
     * consumiu um Completed/Failed/Cancelled e salvou no banco.
     */
    fun consumeTerminal() {
        val s = _state.value
        if (s is GenerationState.Completed ||
            s is GenerationState.Failed ||
            s is GenerationState.Cancelled
        ) {
            _state.value = GenerationState.Idle
        }
    }
}
