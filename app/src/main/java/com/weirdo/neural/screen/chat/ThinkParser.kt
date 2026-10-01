package com.weirdo.neural.screen.chat

/**
 * Separa o conteúdo de uma mensagem em duas partes:
 *
 * - [thinking]: o conteúdo dentro de <think>...</think> (pode estar incompleto)
 * - [final]: a resposta final (após </think>)
 * - [isThinking]: true se o modelo ainda está dentro de um bloco <think> aberto
 *
 * Modelos como Qwen3-Thinking e DeepSeek-R1 emitem esse formato.
 * Se a mensagem não tem <think>, retorna [final] igual ao conteúdo original.
 */
data class ParsedMessage(
    val thinking: String?,
    val final: String,
    val isThinking: Boolean,
) {
    /** Há conteúdo visível para renderizar além do "Thinking..."? */
    val hasFinal: Boolean get() = final.isNotBlank()
}

fun parseThinkBlocks(content: String): ParsedMessage {
    val openTag = "<think>"
    val closeTag = "</think>"

    val openIdx = content.indexOf(openTag)
    if (openIdx < 0) {
        // Não tem bloco de pensamento
        return ParsedMessage(thinking = null, final = content, isThinking = false)
    }

    val thinkStart = openIdx + openTag.length
    val closeIdx = content.indexOf(closeTag, thinkStart)

    return if (closeIdx < 0) {
        // Ainda pensando (bloco aberto, sem fechamento)
        ParsedMessage(
            thinking = content.substring(thinkStart),
            final = "",
            isThinking = true,
        )
    } else {
        // Pensamento fechado, resposta final começa depois
        val afterClose = content.substring(closeIdx + closeTag.length).trimStart()
        ParsedMessage(
            thinking = content.substring(thinkStart, closeIdx),
            final = afterClose,
            isThinking = false,
        )
    }
}
