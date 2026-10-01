package com.weirdo.neural.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weirdo.neural.core.data.prefs.SettingsRepository
import com.weirdo.neural.core.data.repo.ModelsRepository
import com.weirdo.neural.core.llm.engine.LlmEngine
import com.weirdo.neural.core.llm.model.ChatMessage
import com.weirdo.neural.core.llm.model.LoadProgress
import com.weirdo.neural.core.llm.model.ModelConfig
import com.weirdo.neural.core.llm.model.Role
import com.weirdo.neural.core.llm.model.SamplerParams
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: LlmEngine,
    private val settings: SettingsRepository,
    private val modelsRepo: ModelsRepository,
) : ViewModel() {

    /**
     * Uma mensagem da UI. Carrega estatísticas de geração que só fazem
     * sentido para respostas da IA (tokens, tempo, cancelamento).
     */
    data class UiMessage(
        val message: ChatMessage,
        val tokensGenerated: Int = 0,
        val durationMs: Long = 0L,
        val wasCancelled: Boolean = false,
    )

    data class UiState(
        val messages: List<UiMessage> = emptyList(),
        val input: String = "",
        val isGenerating: Boolean = false,
        val isModelLoaded: Boolean = false,
        val modelName: String? = null,
        val statusMessage: String? = null,
        val error: String? = null,
        val tokensGenerated: Int = 0,
        val generationStartMs: Long = 0L,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var loadedModelId: String? = null

    init {
        viewModelScope.launch {
            engine.isLoaded.collect { loaded ->
                _uiState.update { it.copy(isModelLoaded = loaded) }
            }
        }
        viewModelScope.launch {
            engine.loadedModelName.collect { name ->
                _uiState.update { it.copy(modelName = name) }
            }
        }

        viewModelScope.launch {
            settings.activeModelId
                .distinctUntilChanged()
                .collect { modelId ->
                    if (modelId == null) {
                        engine.unload()
                        loadedModelId = null
                        _uiState.update {
                            it.copy(
                                isModelLoaded = false,
                                modelName = null,
                                statusMessage = null,
                                error = null,
                            )
                        }
                    } else if (modelId != loadedModelId) {
                        loadModelById(modelId)
                    }
                }
        }
    }

    private fun loadModelById(modelId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null, statusMessage = "Carregando modelo…") }
            val entity = modelsRepo.findInstalled(modelId)
            if (entity == null) {
                _uiState.update {
                    it.copy(
                        error = "Modelo não encontrado: $modelId",
                        statusMessage = null,
                    )
                }
                return@launch
            }
            val file = File(entity.absolutePath)
            if (!file.exists()) {
                _uiState.update {
                    it.copy(
                        error = "Arquivo não existe: ${entity.fileName}",
                        statusMessage = null,
                    )
                }
                return@launch
            }

            engine.loadModel(
                ModelConfig(
                    path = file.absolutePath,
                    contextSize = entity.contextSize,
                    threads = 4,
                )
            ).collect { progress ->
                when (progress) {
                    is LoadProgress.Loading -> _uiState.update {
                        it.copy(statusMessage = progress.message)
                    }
                    is LoadProgress.Ready -> {
                        loadedModelId = modelId
                        _uiState.update {
                            it.copy(
                                statusMessage = null,
                                error = null,
                                isModelLoaded = true,
                                modelName = progress.modelName,
                            )
                        }
                    }
                    is LoadProgress.Error -> _uiState.update {
                        it.copy(error = progress.message, statusMessage = null)
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || !_uiState.value.isModelLoaded) return

        val userMsg = UiMessage(ChatMessage(Role.USER, text))
        val history = _uiState.value.messages.map { it.message } + userMsg.message

        _uiState.update {
            it.copy(
                messages = _uiState.value.messages + userMsg +
                    UiMessage(ChatMessage(Role.ASSISTANT, "")),
                input = "",
                isGenerating = true,
                error = null,
                tokensGenerated = 0,
                generationStartMs = System.currentTimeMillis(),
            )
        }

        generationJob = viewModelScope.launch {
            val buffer = StringBuilder()
            var tokenCount = 0
            val startMs = System.currentTimeMillis()
            try {
                engine.generate(
                    messages = history,
                    params = SamplerParams(
                        temperature = 0.7f,
                        topK = 40,
                        topP = 0.9f,
                        minP = 0.05f,
                        repeatPenalty = 1.1f,
                        repeatLastN = 64,
                        maxTokens = 2048,
                    ),
                    systemPrompt = null,
                ).collect { token ->
                    buffer.append(token)
                    tokenCount++
                    val snapshot = buffer.toString()
                    val elapsed = System.currentTimeMillis() - startMs
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.dropLast(1) + UiMessage(
                                message = ChatMessage(Role.ASSISTANT, snapshot),
                                tokensGenerated = tokenCount,
                                durationMs = elapsed,
                            ),
                            tokensGenerated = tokenCount,
                        )
                    }
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: "Erro na geração") }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun stop() {
        engine.cancelGeneration()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            if (updated.isNotEmpty()) {
                val last = updated.last()
                if (last.message.role == Role.ASSISTANT) {
                    updated[updated.lastIndex] = last.copy(wasCancelled = true)
                }
            }
            state.copy(messages = updated, isGenerating = false)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
