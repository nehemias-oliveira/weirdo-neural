package com.weirdo.neural.core.llm.llamacpp

import android.util.Log

/**
 * Ponte JNI direta para o llama.cpp.
 * NÃO use diretamente. Use LlamaCppEngine.
 */
internal object LlamaBridge {

    private const val TAG = "LlamaBridge"

    init {
        try {
            System.loadLibrary("weirdo_llm")
            Log.i(TAG, "Biblioteca nativa carregada")
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao carregar biblioteca nativa", t)
            throw t
        }
    }

    external fun nativeBackendInit()
    external fun nativeBackendFree()

    external fun nativeLoadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        useMmap: Boolean,
    ): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeGetChatTemplate(handle: Long): String?

    external fun nativeCountTokens(handle: Long, text: String): Int

    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        maxTokens: Int,
        seed: Int,
        callback: TokenCallback,
    )

    external fun nativeCancel(handle: Long)

    /**
     * Callback que o C++ chama durante a geração.
     * Os nomes dos métodos DEVEM bater com o que está em llama_jni.cpp.
     */
    interface TokenCallback {
        fun onToken(token: String)
        fun onDone()
        fun onError(message: String)
    }
}
