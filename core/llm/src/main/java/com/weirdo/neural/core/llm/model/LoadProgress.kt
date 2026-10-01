package com.weirdo.neural.core.llm.model

sealed class LoadProgress {
    data class Loading(val percent: Int, val message: String) : LoadProgress()
    data class Ready(val modelName: String, val contextSize: Int) : LoadProgress()
    data class Error(val message: String, val cause: Throwable? = null) : LoadProgress()
}
