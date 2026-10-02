package com.weirdo.neural.screen.terminal

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.weirdo.neural.core.data.terminal.TerminalStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(state.output.size) {
        if (state.output.isNotEmpty()) {
            listState.scrollToItem(state.output.size - 1)
        }
    }

    if (copied) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when {
                                state.isPreparing -> state.prepareMessage ?: "Preparando…"
                                state.isRunning -> "Executando…"
                                state.isReady -> "Ubuntu pronto"
                                else -> "Aguardando"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                state.isRunning -> MaterialTheme.colorScheme.tertiary
                                state.isReady -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
                actions = {
                    if (state.output.isNotEmpty()) {
                        IconButton(onClick = {
                            val all = state.output.joinToString("\n") { line ->
                                when (line.stream) {
                                    TerminalStream.STDERR -> "! ${line.text}"
                                    TerminalStream.EXIT -> "exit ${line.text}"
                                    else -> line.text
                                }
                            }
                            clipboard.setText(AnnotatedString(all))
                            copied = true
                        }) {
                            Icon(
                                imageVector = if (copied) Icons.Default.Check
                                              else Icons.Default.ContentCopy,
                                contentDescription = if (copied) "Copiado" else "Copiar tudo",
                                tint = if (copied) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.isRunning) {
                        IconButton(onClick = viewModel::cancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancelar")
                        }
                    }
                    IconButton(onClick = viewModel::clearOutput) {
                        Icon(Icons.Default.Delete, contentDescription = "Limpar")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.prepareError?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            err,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(onClick = viewModel::clearError) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar")
                        }
                    }
                }
            }

            if (state.isPreparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (state.output.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.isPreparing)
                                "Preparando o Ubuntu pela primeira vez…\nIsso leva cerca de 30 segundos."
                            else
                                "Nenhuma saída ainda. Digite um comando.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            items(state.output) { line ->
                                OutputLineRow(line)
                            }
                        }
                    }
                }
            }

            Surface(tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                "comando",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        enabled = state.isReady && !state.isRunning,
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                        leadingIcon = {
                            Text(
                                "$",
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { viewModel.runCommand() },
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.isRunning) {
                        FilledIconButton(onClick = viewModel::cancel) {
                            Icon(Icons.Default.Close, contentDescription = "Parar")
                        }
                    } else {
                        FilledIconButton(
                            onClick = viewModel::runCommand,
                            enabled = state.isReady && state.input.isNotBlank(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Executar",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputLineRow(line: TerminalViewModel.OutputLine) {
    val isPrompt = line.text.startsWith("$ ")
    val isError = line.stream == TerminalStream.STDERR
    val isExit = line.stream == TerminalStream.EXIT

    val annotated = remember(line) {
        buildAnnotatedString {
            when {
                isPrompt -> {
                    // "$ comando" — destaca o prompt e deixa o comando em negrito
                    append("$")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(line.text.removePrefix("$"))
                    }
                }
                isError -> {
                    withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFFFF7B72))) {
                        append("! ")
                    }
                    append(line.text)
                }
                isExit -> {
                    append("· exit ")
                    append(line.text)
                }
                else -> append(line.text)
            }
        }
    }

    val color = when {
        isPrompt -> MaterialTheme.colorScheme.primary
        isError -> MaterialTheme.colorScheme.error
        isExit -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        ),
        color = color,
    )
}
