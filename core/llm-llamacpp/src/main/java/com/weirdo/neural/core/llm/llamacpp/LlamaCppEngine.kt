package com.weirdo.neural.core.llm.llamacpp

import android.util.Log
import com.weirdo.neural.core.llm.engine.LlmEngine
import com.weirdo.neural.core.llm.model.ChatMessage
import com.weirdo.neural.core.llm.model.LoadProgress
import com.weirdo.neural.core.llm.model.ModelConfig
import com.weirdo.neural.core.llm.model.Role
import com.weirdo.neural.core.llm.model.SamplerParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaCppEngine @Inject constructor() : LlmEngine {

    private companion object {
        const val TAG = "LlamaCppEngine"
    }

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()

    private var handle: Long = 0L
    private var chatTemplate: String? = null

    init {
        LlamaBridge.nativeBackendInit()
    }

    override fun loadModel(config: ModelConfig): Flow<LoadProgress> = flow {
        try {
            if (handle != 0L) {
                emit(LoadProgress.Loading(0, "Descarregando modelo anterior..."))
                unload()
            }

            val file = File(config.path)
            if (!file.exists()) {
                emit(LoadProgress.Error("Arquivo não encontrado: ${config.path}"))
                return@flow
            }

            emit(LoadProgress.Loading(5, "Preparando..."))

            val loaded = withContext(Dispatchers.IO) {
                LlamaBridge.nativeLoadModel(
                    path = config.path,
                    nCtx = config.contextSize,
                    nThreads = config.threads,
                    useMmap = config.useMmap,
                )
            }

            if (loaded == 0L) {
                emit(LoadProgress.Error("Falha ao carregar modelo (JNI retornou 0)"))
                return@flow
            }

            handle = loaded
            chatTemplate = LlamaBridge.nativeGetChatTemplate(handle)

            _isLoaded.value = true
            _loadedModelName.value = file.name

            emit(LoadProgress.Ready(
                modelName = file.name,
                contextSize = config.contextSize,
            ))
            Log.i(TAG, "Modelo carregado: ${file.name}")

        } catch (t: Throwable) {
            Log.e(TAG, "Erro ao carregar modelo", t)
            emit(LoadProgress.Error(t.message ?: "Erro desconhecido", t))
        }
    }.flowOn(Dispatchers.IO)

    override fun generate(
        messages: List<ChatMessage>,
        params: SamplerParams,
        systemPrompt: String?,
    ): Flow<String> = callbackFlow {
        if (handle == 0L) {
            close(IllegalStateException("Nenhum modelo carregado"))
            return@callbackFlow
        }

        val prompt = buildPrompt(messages, systemPrompt)
        Log.d(TAG, "Prompt montado (${prompt.length} chars)")

        val callback = object : LlamaBridge.TokenCallback {
            override fun onToken(token: String) {
                trySend(token)
            }

            override fun onDone() {
                close()
            }

            override fun onError(message: String) {
                close(IllegalStateException(message))
            }
        }

        // Lança no escopo do callbackFlow — cancelado automaticamente ao fechar
        launch(Dispatchers.IO) {
            LlamaBridge.nativeGenerate(
                handle = handle,
                prompt = prompt,
                temperature = params.temperature,
                topK = params.topK,
                topP = params.topP,
                minP = params.minP,
                repeatPenalty = params.repeatPenalty,
                repeatLastN = params.repeatLastN,
                maxTokens = params.maxTokens,
                seed = params.seed,
                callback = callback,
            )
        }

        awaitClose {
            LlamaBridge.nativeCancel(handle)
        }
    }

    override fun countTokens(text: String): Int {
        if (handle == 0L) return -1
        return LlamaBridge.nativeCountTokens(handle, text)
    }

    override fun cancelGeneration() {
        if (handle != 0L) LlamaBridge.nativeCancel(handle)
    }

    override fun unload() {
        if (handle != 0L) {
            LlamaBridge.nativeFreeModel(handle)
            handle = 0L
            chatTemplate = null
        }
        _isLoaded.value = false
        _loadedModelName.value = null
    }

    private fun buildPrompt(messages: List<ChatMessage>, systemPrompt: String?): String {
        return buildString {
            systemPrompt?.let {
                append("<|im_start|>system\n").append(it).append("<|im_end|>\n")
            }
            messages.forEach { msg ->
                val role = when (msg.role) {
                    Role.SYSTEM    -> "system"
                    Role.USER      -> "user"
                    Role.ASSISTANT -> "assistant"
                    Role.TOOL      -> "tool"
                }
                append("<|im_start|>").append(role).append("\n")
                append(msg.content)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }
}
