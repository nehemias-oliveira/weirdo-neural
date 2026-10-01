package com.weirdo.neural.screen.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weirdo.neural.core.data.prefs.SettingsRepository
import com.weirdo.neural.core.llm.engine.LlmEngine
import com.weirdo.neural.core.llm.model.ChatMessage
import com.weirdo.neural.core.llm.model.LoadProgress
import com.weirdo.neural.core.llm.model.ModelConfig
import com.weirdo.neural.core.llm.model.Role
import com.weirdo.neural.core.llm.model.SamplerParams
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val engine: LlmEngine,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val messages: List<ChatMessage> = emptyList(),
        val input: String = "",
        val isGenerating: Boolean = false,
        val isModelLoaded: Boolean = false,
        val modelName: String? = null,
        val statusMessage: String? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var generationJob: Job? = null

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
        // Auto-carrega o último modelo se o engine ainda não estiver carregado
        viewModelScope.launch {
            if (!engine.isLoaded.value) {
                val path = settings.lastModelPath.first()
                if (!path.isNullOrBlank() && File(path).exists()) {
                    loadModelFromPath(path)
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun loadModelFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(statusMessage = "Importando modelo...", error = null) }
                val path = withContext(Dispatchers.IO) { importModel(uri) }
                loadModelFromPath(path)
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(error = "Erro ao importar: ${t.message}", statusMessage = null)
                }
            }
        }
    }

    private fun importModel(uri: Uri): String {
        val modelsDir = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
        val displayName = queryDisplayName(uri) ?: "model.gguf"
        val target = File(modelsDir, displayName)

        // Se já existe com tamanho > 0, assume que já foi copiado
        if (target.exists() && target.length() > 0) return target.absolutePath

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, bufferSize = 1024 * 1024)
            }
        } ?: throw IllegalStateException("Não foi possível abrir o arquivo")

        return target.absolutePath
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return null
    }

    private fun loadModelFromPath(path: String) {
        viewModelScope.launch {
            engine.loadModel(ModelConfig(path = path)).collect { progress ->
                when (progress) {
                    is LoadProgress.Loading -> _uiState.update {
                        it.copy(statusMessage = progress.message)
                    }
                    is LoadProgress.Ready -> {
                        _uiState.update {
                            it.copy(
                                statusMessage = null,
                                error = null,
                                isModelLoaded = true,
                                modelName = progress.modelName,
                            )
                        }
                        settings.setLastModelPath(path)
                    }
                    is LoadProgress.Error -> _uiState.update {
                        it.copy(error = progress.message, statusMessage = null)
                    }
                }
            }
        }
    }

    fun send() {
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || !_uiState.value.isModelLoaded) return

        val userMsg = ChatMessage(Role.USER, text)
        val history = _uiState.value.messages + userMsg

        _uiState.update {
            it.copy(
                messages = history + ChatMessage(Role.ASSISTANT, ""),
                input = "",
                isGenerating = true,
                error = null,
            )
        }

        generationJob = viewModelScope.launch {
            val buffer = StringBuilder()
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
                        maxTokens = 512,
                    ),
                    systemPrompt = null,
                ).collect { token ->
                    buffer.append(token)
                    val snapshot = buffer.toString()
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.dropLast(1) +
                                ChatMessage(Role.ASSISTANT, snapshot)
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
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
