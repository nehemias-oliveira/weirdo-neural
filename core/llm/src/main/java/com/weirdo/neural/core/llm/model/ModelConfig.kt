package com.weirdo.neural.core.llm.model

data class ModelConfig(
    val path: String,
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
)
