package com.weirdo.neural.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weirdo.neural.core.data.db.entity.ConversationEntity
import com.weirdo.neural.core.llm.model.Role
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToModels: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val isAtBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= state.messages.size - 2
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.message?.content) {
        if (state.messages.isNotEmpty() && isAtBottom) {
            listState.scrollToItem(state.messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationsDrawer(
                conversations = state.conversations,
                currentId = state.conversationId,
                onSelect = { id ->
                    viewModel.openConversation(id)
                    scope.launch { drawerState.close() }
                },
                onDelete = { id -> viewModel.deleteConversation(id) },
                onNew = {
                    viewModel.createNewConversation()
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Conversas")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = state.conversationTitle,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            val subtitle = state.activeModelDisplayName
                                ?: "Nenhum modelo selecionado"
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    state.isModelLoaded -> MaterialTheme.colorScheme.primary
                                    state.isLoadingModel -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    },
                    actions = {
                        if (state.isLoadingModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(
                                onClick = viewModel::toggleModelEnabled,
                                enabled = state.activeModelId != null,
                            ) {
                                Icon(
                                    Icons.Default.PowerSettingsNew,
                                    contentDescription = if (state.isModelLoaded)
                                        "Descarregar modelo" else "Carregar modelo",
                                    tint = if (state.isModelLoaded)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToModels) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Trocar modelo")
                        }
                    },
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                state.error?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Text(err, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = viewModel::clearError) {
                                Icon(Icons.Default.Close, contentDescription = "Fechar")
                            }
                        }
                    }
                }

                state.statusMessage?.let { msg ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = msg,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                if (state.messages.isEmpty()) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val msg = when {
                            state.activeModelId == null ->
                                "Nenhum modelo selecionado.\nVá em \"Modelos\" e escolha um."
                            !state.isModelLoaded ->
                                "Modelo pronto para carregar.\nToque no ícone de energia acima."
                            else ->
                                "Modelo carregado. Manda a primeira mensagem."
                        }
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.activeModelId == null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onNavigateToModels) {
                                Text("Abrir Modelos")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        itemsIndexed(
                            items = state.messages,
                            key = { index, _ -> index },
                        ) { _, item ->
                            MessageBubble(item)
                        }
                    }
                }

                if (state.isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val elapsed = (System.currentTimeMillis() - state.generationStartMs) / 1000
                        Text(
                            text = "${state.tokensGenerated} tokens · ${elapsed}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = state.input,
                            onValueChange = viewModel::onInputChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Digite...") },
                            enabled = state.isModelLoaded && !state.isGenerating,
                            maxLines = 5,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (state.isGenerating) {
                            FilledIconButton(onClick = viewModel::stop) {
                                Icon(Icons.Default.Close, contentDescription = "Parar")
                            }
                        } else {
                            FilledIconButton(
                                onClick = viewModel::send,
                                enabled = state.isModelLoaded && state.input.isNotBlank(),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Enviar",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationsDrawer(
    conversations: List<ConversationEntity>,
    currentId: Long?,
    onSelect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
) {
    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Conversas",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = "Nova conversa")
            }
        }

        HorizontalDivider()

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nenhuma conversa ainda",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(
                    items = conversations,
                    key = { _, c -> c.id },
                ) { _, convo ->
                    ConversationRow(
                        conversation = convo,
                        isCurrent = convo.id == currentId,
                        onClick = { onSelect(convo.id) },
                        onDelete = { onDelete(convo.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (isCurrent)
            MaterialTheme.colorScheme.primaryContainer
        else
            Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    text = formatRelativeTime(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Excluir",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRelativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> "agora"
        diff < 3_600_000L -> "${diff / 60_000L}min atrás"
        diff < 86_400_000L -> "${diff / 3_600_000L}h atrás"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L}d atrás"
        else -> java.text.SimpleDateFormat(
            "dd/MM/yy",
            java.util.Locale.getDefault(),
        ).format(java.util.Date(ms))
    }
}

@Composable
private fun MessageBubble(item: ChatViewModel.UiMessage) {
    val msg = item.message
    val isUser = msg.role == Role.USER
    val parsed = remember(msg.content) { parseThinkBlocks(msg.content) }
    val showStats = !isUser && item.tokensGenerated > 0

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && parsed.thinking != null) {
            ThinkingBlock(
                content = parsed.thinking,
                isThinking = parsed.isThinking,
            )
            if (parsed.hasFinal) Spacer(Modifier.height(6.dp))
        }

        val finalText = if (isUser) msg.content else parsed.final
        val shouldRenderFinal = isUser || parsed.hasFinal ||
            (!parsed.isThinking && parsed.thinking == null)

        if (shouldRenderFinal && finalText.isNotEmpty()) {
            if (isUser) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(
                        text = finalText,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                AssistantContent(finalText)
            }
        }

        if (showStats) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("${item.tokensGenerated} tokens")
                    val secs = item.durationMs / 1000.0
                    if (secs > 0) append(" · %.1fs".format(secs))
                    if (item.tokensGenerated > 0 && secs > 0) {
                        val tps = item.tokensGenerated / secs
                        append(" · %.1f t/s".format(tps))
                    }
                    if (item.wasCancelled) append(" · cancelado")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun AssistantContent(content: String) {
    val segments = remember(content) { parseCodeSegments(content) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEach { seg ->
            when (seg) {
                is CodeSegment.Text -> {
                    val trimmed = seg.content.trim()
                    if (trimmed.isNotEmpty()) {
                        Text(
                            text = trimmed,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is CodeSegment.Code -> CodeBlock(seg)
            }
        }
    }
}

@Composable
private fun CodeBlock(seg: CodeSegment.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (seg.language.isNotBlank()) seg.language else "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(seg.content))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check
                                      else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copiado" else "Copiar",
                        modifier = Modifier.size(16.dp),
                        tint = if (copied) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = seg.content.trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (seg.isOpen) {
                Text(
                    text = "⋯ gerando",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (copied) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }
}

@Composable
private fun ThinkingBlock(content: String, isThinking: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isThinking) "Pensando…" else "Raciocínio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Recolher" else "Expandir",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
