package com.weirdo.neural.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModelCatalog(
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String = "",
    val models: List<ModelInfo> = emptyList(),
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val url: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val contextSize: Int = 4096,
    val tags: List<String> = emptyList(),
    val recommendedFor: String = "",
    /**
     * SHA256 opcional. Se presente, o download é verificado.
     * Se ausente, o app pula a verificação (log de warning).
     */
    val sha256: String? = null,
)
