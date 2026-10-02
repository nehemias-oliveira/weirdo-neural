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
import com.weirdo.neural.service.GenerationController
import com.weirdo.neural.service.GenerationState
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val generation: GenerationController,
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

    private var loadedModelId: String? = null

    init {
        observeEngine()
        observeActiveModel()
        observeConversations()
        observeGeneration()
        openRecentOrCreate()
    }

    // -----------------------------------------------------------------------
    // Observadores
    // -----------------------------------------------------------------------

    private fun observeEngine() {
        viewModelScope.launch {
            engine.isLoaded.collect { loaded ->
                _uiState.update { it.copy(isModelLoaded = loaded) }
            }
        }
    }

    private fun observeActiveModel() {
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
    }

    private fun observeConversations() {
        viewModelScope.launch {
            conversationRepo.observeAll().collect { list ->
                _uiState.update { it.copy(conversations = list) }
            }
        }
    }

    /**
     * Observa o GenerationController. Isso é o que sobrevive a mudanças
     * de tela: se o usuário sair do chat e voltar, o estado continua sendo
     * refletido porque a geração vive no controller (escopo da aplicação).
     */
    private fun observeGeneration() {
        viewModelScope.launch {
            generation.state.collect { gs ->
                when (gs) {
                    is GenerationState.Idle -> {
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                tokensGenerated = 0,
                                generationStartMs = 0L,
                            )
                        }
                    }

                    is GenerationState.Running -> {
                        val elapsed = System.currentTimeMillis() - gs.startedAt
                        _uiState.update { state ->
                            val updated = if (state.messages.isEmpty() ||
                                state.messages.last().message.role != Role.ASSISTANT
                            ) {
                                // Caso raro: voltamos e o estado foi perdido.
                                // Recria a mensagem do assistente com o buffer atual.
                                state.messages + UiMessage(
                                    message = ChatMessage(Role.ASSISTANT, gs.buffer),
                                    tokensGenerated = gs.tokensGenerated,
                                    durationMs = elapsed,
                                )
                            } else {
                                state.messages.dropLast(1) + UiMessage(
                                    message = ChatMessage(Role.ASSISTANT, gs.buffer),
                                    tokensGenerated = gs.tokensGenerated,
                                    durationMs = elapsed,
                                )
                            }
                            state.copy(
                                messages = updated,
                                isGenerating = true,
                                tokensGenerated = gs.tokensGenerated,
                                generationStartMs = gs.startedAt,
                            )
                        }
                    }

                    is GenerationState.Completed -> {
                        val convId = _uiState.value.conversationId
                        val finalText = gs.finalText
                        if (convId != null && finalText.isNotBlank()) {
                            viewModelScope.launch {
                                conversationRepo.addMessage(
                                    conversationId = convId,
                                    role = "assistant",
                                    content = finalText,
                                    modelId = gs.modelId,
                                )
                            }
                        }
                        _uiState.update { state ->
                            val updated = if (state.messages.isEmpty() ||
                                state.messages.last().message.role != Role.ASSISTANT
                            ) {
                                state.messages + UiMessage(
                                    message = ChatMessage(Role.ASSISTANT, finalText),
                                    tokensGenerated = gs.tokensGenerated,
                                    durationMs = gs.durationMs,
                                )
                            } else {
                                state.messages.dropLast(1) + UiMessage(
                                    message = ChatMessage(Role.ASSISTANT, finalText),
                                    tokensGenerated = gs.tokensGenerated,
                                    durationMs = gs.durationMs,
                                )
                            }
                            state.copy(
                                messages = updated,
                                isGenerating = false,
                                tokensGenerated = 0,
                                generationStartMs = 0L,
                            )
                        }
                        generation.consumeTerminal()
                    }

                    is GenerationState.Failed -> {
                        val convId = _uiState.value.conversationId
                        val partial = gs.partialText
                        if (convId != null && !partial.isNullOrBlank()) {
                            viewModelScope.launch {
                                conversationRepo.addMessage(
                                    conversationId = convId,
                                    role = "assistant",
                                    content = "$partial\n\n_[interrompido: ${gs.message}]_",
                                    modelId = null,
                                )
                            }
                        }
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                error = gs.message,
                                tokensGenerated = 0,
                                generationStartMs = 0L,
                            )
                        }
                        generation.consumeTerminal()
                    }

                    is GenerationState.Cancelled -> {
                        val convId = _uiState.value.conversationId
                        val partial = (_uiState.value.messages.lastOrNull()
                            ?.takeIf { it.message.role == Role.ASSISTANT }
                            ?.message?.content) ?: ""
                        if (convId != null && partial.isNotBlank()) {
                            viewModelScope.launch {
                                conversationRepo.addMessage(
                                    conversationId = convId,
                                    role = "assistant",
                                    content = "$partial\n\n_[cancelado]_",
                                    modelId = null,
                                )
                            }
                        }
                        _uiState.update { state ->
                            val updated = state.messages.toMutableList()
                            if (updated.isNotEmpty() &&
                                updated.last().message.role == Role.ASSISTANT
                            ) {
                                updated[updated.lastIndex] =
                                    updated.last().copy(wasCancelled = true)
                            }
                            state.copy(
                                messages = updated,
                                isGenerating = false,
                                tokensGenerated = 0,
                                generationStartMs = 0L,
                            )
                        }
                        generation.consumeTerminal()
                    }
                }
            }
        }
    }

    private fun openRecentOrCreate() {
        viewModelScope.launch {
            val recent = conversationRepo.findMostRecent()
            if (recent != null) {
                openConversationInternal(recent.id)
            } else {
                createNewConversationInternal()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Conversas
    // -----------------------------------------------------------------------

    fun createNewConversation() {
        viewModelScope.launch { createNewConversationInternal() }
    }

    private suspend fun createNewConversationInternal() {
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

    fun openConversation(conversationId: Long) {
        viewModelScope.launch { openConversationInternal(conversationId) }
    }

    private suspend fun openConversationInternal(conversationId: Long) {
        if (_uiState.value.isGenerating) {
            _uiState.update {
                it.copy(error = "Aguarde a geração terminar antes de trocar de conversa.")
            }
            return
        }
        val entity = conversationRepo.findConversation(conversationId) ?: return
        val messages = conversationRepo.listMessages(conversationId)
        _uiState.update {
            it.copy(
                conversationId = conversationId,
                conversationTitle = entity.title,
                messages = messages.map { m ->
                    UiMessage(ChatMessage(role = m.role.toRole(), content = m.content))
                },
                input = "",
                error = null,
            )
        }
    }

    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversationId)
            if (_uiState.value.conversationId == conversationId) {
                val recent = conversationRepo.findMostRecent()
                if (recent != null) openConversationInternal(recent.id)
                else createNewConversationInternal()
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
                it.copy(isModelLoaded = false, isLoadingModel = false, statusMessage = null)
            }
        } else {
            val modelId = _uiState.value.activeModelId
            if (modelId == null) {
                _uiState.update {
                    it.copy(error = "Nenhum modelo selecionado. Vá em Modelos.")
                }
                return
            }
            if (loadedModelId != modelId) loadModelById(modelId)
        }
    }

    private fun loadModelById(modelId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(error = null, isLoadingModel = true, statusMessage = "Carregando modelo…")
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
    // Envio
    // -----------------------------------------------------------------------

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || !_uiState.value.isModelLoaded) return
        if (_uiState.value.isGenerating) return
        val convId = _uiState.value.conversationId ?: return

        val userMsg = UiMessage(ChatMessage(Role.USER, text))
        val history = _uiState.value.messages.map { it.message } + userMsg.message
        val isFirst = _uiState.value.messages.isEmpty()

        // UI: adiciona user + assistant vazio
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg + UiMessage(ChatMessage(Role.ASSISTANT, "")),
                input = "",
                error = null,
            )
        }

        // Persiste o user
        viewModelScope.launch {
            conversationRepo.addMessage(
                conversationId = convId,
                role = "user",
                content = text,
                generateTitle = isFirst,
            )
            if (isFirst) {
                val entity = conversationRepo.findConversation(convId)
                if (entity != null) {
                    _uiState.update { it.copy(conversationTitle = entity.title) }
                }
            }
        }

        // Inicia a geração no controller
        generation.start(
            history = history,
            params = SamplerParams(
                temperature = 0.7f,
                topK = 40,
                topP = 0.9f,
                minP = 0.05f,
                repeatPenalty = 1.1f,
                repeatLastN = 64,
                maxTokens = 2048,
            ),
            modelId = _uiState.value.activeModelId,
        )
    }

    fun stop() {
        generation.cancel()
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
