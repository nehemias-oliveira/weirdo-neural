package com.weirdo.neural.core.data.repo

import com.weirdo.neural.core.data.db.dao.ConversationDao
import com.weirdo.neural.core.data.db.dao.MessageDao
import com.weirdo.neural.core.data.db.entity.ConversationEntity
import com.weirdo.neural.core.data.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {

    fun observeAll(): Flow<List<ConversationEntity>> = conversationDao.observeAll()

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        messageDao.observeForConversation(conversationId)

    suspend fun listMessages(conversationId: Long): List<MessageEntity> =
        messageDao.listForConversation(conversationId)

    suspend fun findConversation(id: Long): ConversationEntity? =
        conversationDao.findById(id)

    suspend fun findMostRecent(): ConversationEntity? =
        conversationDao.findMostRecent()

    /** Cria uma nova conversa vazia e retorna o ID gerado. */
    suspend fun createConversation(title: String = "Nova conversa"): Long {
        val now = System.currentTimeMillis()
        return conversationDao.insert(
            ConversationEntity(title = title, createdAt = now, updatedAt = now)
        )
    }

    /**
     * Insere uma mensagem e atualiza o updatedAt da conversa.
     * A primeira mensagem do usuário também pode gerar um título automático.
     */
    suspend fun addMessage(
        conversationId: Long,
        role: String,
        content: String,
        modelId: String? = null,
        generateTitle: Boolean = false,
    ): Long {
        val id = messageDao.insert(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                modelId = modelId,
            )
        )
        conversationDao.touch(conversationId)
        if (generateTitle) {
            val title = content.trim().lineSequence().firstOrNull()
                ?.take(40)
                ?.ifBlank { null }
            if (title != null) {
                conversationDao.updateTitle(conversationId, title)
            }
        }
        return id
    }

    suspend fun renameConversation(id: Long, title: String) {
        conversationDao.updateTitle(id, title)
    }

    suspend fun deleteConversation(id: Long) {
        // Foreign key CASCADE cuida das mensagens, mas por segurança limpamos aqui
        messageDao.deleteAllForConversation(id)
        conversationDao.delete(id)
    }

    suspend fun countMessages(conversationId: Long): Int =
        messageDao.countForConversation(conversationId)
}
