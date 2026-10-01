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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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

    // Auto-scroll quando novas linhas chegam
    LaunchedEffect(state.output.size) {
        if (state.output.isNotEmpty()) {
            listState.scrollToItem(state.output.size - 1)
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
                                state.isReady -> "Alpine pronto"
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
                        Text(err, modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = viewModel::clearError) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar")
                        }
                    }
                }
            }

            if (state.isPreparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Área de output
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            ) {
                if (state.output.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.isPreparing)
                                "Preparando Alpine pela primeira vez…\nIsso leva cerca de 30 segundos."
                            else
                                "Nenhuma saída ainda. Digite um comando.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.output) { line ->
                            OutputLineRow(line)
                        }
                    }
                }
            }

            // Campo de input
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("$ comando") },
                        enabled = state.isReady && !state.isRunning,
                        maxLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
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
    val color = when (line.stream) {
        TerminalStream.STDOUT -> MaterialTheme.colorScheme.onSurface
        TerminalStream.STDERR -> MaterialTheme.colorScheme.error
        TerminalStream.EXIT -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val prefix = when (line.stream) {
        TerminalStream.STDERR -> "! "
        TerminalStream.EXIT -> "exit "
        else -> ""
    }
    Text(
        text = "$prefix${line.text}",
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        ),
        color = color,
    )
}
