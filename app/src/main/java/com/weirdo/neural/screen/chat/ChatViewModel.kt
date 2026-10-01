package com.weirdo.neural.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weirdo.neural.core.data.db.entity.ConversationEntity
import com.weirdo.neural.core.data.prefs.SettingsRepository
import com.weirdo.neural.core.data.repo.ConversationRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: LlmEngine,
    private val settings: SettingsRepository,
    private val modelsRepo: ModelsRepository,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {

    data class UiMessage(
        val message: ChatMessage,
        val tokensGenerated: Int = 0,
        val durationMs: Long = 0L,
        val wasCancelled: Boolean = false,
    )

    data class UiState(
        val conversationId: Long? = null,
        val conversationTitle: String = "Nova conversa",
        val conversations: List<ConversationEntity> = emptyList(),
        val messages: List<UiMessage> = emptyList(),
        val input: String = "",
        val isGenerating: Boolean = false,
        val isModelLoaded: Boolean = false,
        val isLoadingModel: Boolean = false,
        val activeModelId: String? = null,
        val activeModelDisplayName: String? = null,
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

        // Observa modelo ativo escolhido na aba Modelos
        viewModelScope.launch {
            settings.activeModelId.collect { modelId ->
                val previousId = _uiState.value.activeModelId
                if (modelId == previousId) return@collect

                if (_uiState.value.isModelLoaded) {
                    engine.unload()
                    loadedModelId = null
                }

                val displayName = if (modelId == null) null
                    else modelsRepo.findInstalled(modelId)?.displayName

                _uiState.update {
                    it.copy(
                        activeModelId = modelId,
                        activeModelDisplayName = displayName,
                        isModelLoaded = false,
                        statusMessage = null,
                        error = null,
                    )
                }
            }
        }

        // Observa a lista de conversas
        viewModelScope.launch {
            conversationRepo.observeAll().collect { list ->
                _uiState.update { it.copy(conversations = list) }
            }
        }

        // Reabre a conversa mais recente ou cria uma nova
        viewModelScope.launch {
            val recent = conversationRepo.findMostRecent()
            if (recent != null) {
                openConversation(recent.id)
            } else {
                createNewConversation()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Conversas
    // -----------------------------------------------------------------------

    fun createNewConversation() {
        viewModelScope.launch {
            val id = conversationRepo.createConversation()
            _uiState.update {
                it.copy(
                    conversationId = id,
                    conversationTitle = "Nova conversa",
                    messages = emptyList(),
                    input = "",
                    error = null,
                    tokensGenerated = 0,
                )
            }
        }
    }

    fun openConversation(conversationId: Long) {
        viewModelScope.launch {
            val entity = conversationRepo.findConversation(conversationId)
                ?: return@launch
            // Não permite trocar durante geração
            if (_uiState.value.isGenerating) {
                _uiState.update {
                    it.copy(error = "Aguarde a geração terminar antes de trocar de conversa.")
                }
                return@launch
            }
            val messages = conversationRepo.listMessages(conversationId)
            _uiState.update {
                it.copy(
                    conversationId = conversationId,
                    conversationTitle = entity.title,
                    messages = messages.map { m ->
                        UiMessage(
                            message = ChatMessage(
                                role = m.role.toRole(),
                                content = m.content,
                            ),
                        )
                    },
                    input = "",
                    error = null,
                )
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversationId)
            // Se apagou a atual, abre a próxima ou cria uma nova
            if (_uiState.value.conversationId == conversationId) {
                val recent = conversationRepo.findMostRecent()
                if (recent != null) {
                    openConversation(recent.id)
                } else {
                    createNewConversation()
                }
            }
        }
    }

    fun renameCurrentConversation(title: String) {
        val id = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            conversationRepo.renameConversation(id, title)
            _uiState.update { it.copy(conversationTitle = title) }
        }
    }

    // -----------------------------------------------------------------------
    // Modelo
    // -----------------------------------------------------------------------

    fun toggleModelEnabled() {
        if (_uiState.value.isModelLoaded) {
            engine.unload()
            loadedModelId = null
            _uiState.update {
                it.copy(
                    isModelLoaded = false,
                    isLoadingModel = false,
                    statusMessage = null,
                )
            }
        } else {
            val modelId = _uiState.value.activeModelId
            if (modelId == null) {
                _uiState.update {
                    it.copy(error = "Nenhum modelo selecionado. Vá em Modelos.")
                }
                return
            }
            if (loadedModelId != modelId) {
                loadModelById(modelId)
            }
        }
    }

    private fun loadModelById(modelId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    error = null,
                    isLoadingModel = true,
                    statusMessage = "Carregando modelo…",
                )
            }
            val entity = modelsRepo.findInstalled(modelId)
            if (entity == null) {
                _uiState.update {
                    it.copy(
                        error = "Modelo não encontrado: $modelId",
                        statusMessage = null,
                        isLoadingModel = false,
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
                        isLoadingModel = false,
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
                                isLoadingModel = false,
                            )
                        }
                    }
                    is LoadProgress.Error -> _uiState.update {
                        it.copy(
                            error = progress.message,
                            statusMessage = null,
                            isLoadingModel = false,
                        )
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Input + geração
    // -----------------------------------------------------------------------

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || !_uiState.value.isModelLoaded) return
        val conversationId = _uiState.value.conversationId ?: return

        val userMsg = UiMessage(ChatMessage(Role.USER, text))
        val history = _uiState.value.messages.map { it.message } + userMsg.message
        val isFirstMessage = _uiState.value.messages.isEmpty()

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

        // Persiste a mensagem do usuário
        viewModelScope.launch {
            conversationRepo.addMessage(
                conversationId = conversationId,
                role = "user",
                content = text,
                generateTitle = isFirstMessage,
            )
            if (isFirstMessage) {
                // Atualiza o título exibido
                val entity = conversationRepo.findConversation(conversationId)
                if (entity != null) {
                    _uiState.update { it.copy(conversationTitle = entity.title) }
                }
            }
        }

        val activeModelId = _uiState.value.activeModelId

        generationJob = viewModelScope.launch {
            val buffer = StringBuilder()
            var tokenCount = 0
            val startMs = System.currentTimeMillis()
            var lastEmitMs = 0L
            val emitIntervalMs = 60L

            fun emitSnapshot(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastEmitMs < emitIntervalMs) return
                lastEmitMs = now
                val snapshot = buffer.toString()
                val elapsed = now - startMs
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
                    emitSnapshot()
                }
                emitSnapshot(force = true)

                // Persiste a resposta completa
                val finalContent = buffer.toString()
                if (finalContent.isNotBlank()) {
                    conversationRepo.addMessage(
                        conversationId = conversationId,
                        role = "assistant",
                        content = finalContent,
                        modelId = activeModelId,
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = t.message ?: "Erro na geração") }
                // Persiste o que foi gerado até agora (se algo)
                val partial = buffer.toString()
                if (partial.isNotBlank()) {
                    conversationRepo.addMessage(
                        conversationId = conversationId,
                        role = "assistant",
                        content = "$partial\n\n_[interrompido]_",
                        modelId = activeModelId,
                    )
                }
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

private fun String.toRole(): Role = when (this.lowercase()) {
    "system"    -> Role.SYSTEM
    "user"      -> Role.USER
    "assistant" -> Role.ASSISTANT
    "tool"      -> Role.TOOL
    else        -> Role.USER
}
