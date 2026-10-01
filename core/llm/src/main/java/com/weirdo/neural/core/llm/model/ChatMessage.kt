package com.weirdo.neural.core.llm.model

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(
    val role: Role,
    val content: String,
)
