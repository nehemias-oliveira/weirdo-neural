package com.weirdo.neural.screen.chat

/**
 * Segmento do texto do assistente: ou é texto normal, ou é um bloco de código.
 */
sealed interface CodeSegment {

    data class Text(val content: String) : CodeSegment

    data class Code(
        val language: String,
        val content: String,
        val isOpen: Boolean,
    ) : CodeSegment
}

private const val FENCE = "```"

/**
 * Quebra [content] em segmentos de texto e blocos de código.
 * Suporta múltiplos blocos e blocos incompletos durante o streaming.
 */
fun parseCodeSegments(content: String): List<CodeSegment> {
    if (FENCE !in content) {
        return listOf(CodeSegment.Text(content))
    }

    val out = mutableListOf<CodeSegment>()
    var i = 0

    while (i < content.length) {
        val start = content.indexOf(FENCE, i)
        if (start < 0) {
            if (i < content.length) out += CodeSegment.Text(content.substring(i))
            break
        }

        if (start > i) {
            out += CodeSegment.Text(content.substring(i, start))
        }

        val afterFence = start + FENCE.length
        val newlineIdx = content.indexOf('\n', afterFence)

        if (newlineIdx < 0) {
            val lang = content.substring(afterFence).trim()
            out += CodeSegment.Code(lang, "", isOpen = true)
            break
        }

        val language = content.substring(afterFence, newlineIdx).trim()
        val bodyStart = newlineIdx + 1

        val closeIdx = content.indexOf(FENCE, bodyStart)
        if (closeIdx < 0) {
            out += CodeSegment.Code(
                language = language,
                content = content.substring(bodyStart),
                isOpen = true,
            )
            break
        }

        out += CodeSegment.Code(
            language = language,
            content = content.substring(bodyStart, closeIdx),
            isOpen = false,
        )
        i = closeIdx + FENCE.length
    }

    return out
}
