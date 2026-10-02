package com.weirdo.neural.screen.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weirdo.neural.core.data.terminal.PrepareProgress
import com.weirdo.neural.core.data.terminal.TerminalManager
import com.weirdo.neural.core.data.terminal.TerminalStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val terminal: TerminalManager,
) : ViewModel() {

    data class OutputLine(
        val stream: TerminalStream,
        val text: String,
    )

    data class UiState(
        val isPreparing: Boolean = false,
        val prepareMessage: String? = null,
        val prepareError: String? = null,
        val isReady: Boolean = false,
        val isRunning: Boolean = false,
        val currentCommand: String? = null,
        val input: String = "",
        val history: List<String> = emptyList(),
        val historyIndex: Int = -1,
        val output: List<OutputLine> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState(isReady = terminal.isReady))
    val uiState = _uiState.asStateFlow()

    private var executeJob: Job? = null

    init {
        if (!terminal.isReady) {
            prepare()
        } else {
            appendLocal("Ubuntu pronto. Digite um comando (ex: uname -a).", TerminalStream.STDOUT)
        }
    }

    fun prepare() {
        if (_uiState.value.isPreparing) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isPreparing = true, prepareMessage = "Preparando…", prepareError = null)
            }
            terminal.prepare().collect { progress ->
                when (progress) {
                    is PrepareProgress.Starting ->
                        _uiState.update { it.copy(prepareMessage = "Iniciando…") }

                    is PrepareProgress.ExtractingBinaries ->
                        _uiState.update { it.copy(prepareMessage = "Copiando binários…") }

                    is PrepareProgress.ExtractingRootfs ->
                        _uiState.update {
                            it.copy(prepareMessage = "Extraindo rootfs (${progress.percent}%)…")
                        }

                    is PrepareProgress.Ready -> {
                        _uiState.update {
                            it.copy(
                                isPreparing = false,
                                prepareMessage = null,
                                isReady = true,
                                prepareError = null,
                            )
                        }
                        appendLocal("Ubuntu pronto. Digite um comando.", TerminalStream.STDOUT)
                    }

                    is PrepareProgress.Error -> {
                        _uiState.update {
                            it.copy(
                                isPreparing = false,
                                prepareMessage = null,
                                prepareError = progress.message,
                            )
                        }
                        appendLocal("Erro: ${progress.message}", TerminalStream.STDERR)
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text, historyIndex = -1) }
    }

    /** Navega para o comando anterior (delta = -1) ou próximo (delta = +1). */
    fun navigateHistory(delta: Int) {
        val state = _uiState.value
        if (state.history.isEmpty()) return
        val current = state.historyIndex
        val next = when {
            current < 0 && delta < 0 -> state.history.size - 1
            current < 0 -> return
            else -> (current + delta).coerceIn(-1, state.history.size - 1)
        }
        val text = if (next < 0) "" else state.history[next]
        _uiState.update { it.copy(input = text, historyIndex = next) }
    }

    fun runCommand() {
        val cmd = _uiState.value.input.trim()
        if (cmd.isEmpty() || !_uiState.value.isReady || _uiState.value.isRunning) return

        _uiState.update {
            it.copy(
                input = "",
                historyIndex = -1,
                isRunning = true,
                currentCommand = cmd,
                history = it.history + cmd,
            )
        }
        appendLocal("$ $cmd", TerminalStream.STDOUT)

        executeJob = viewModelScope.launch {
            terminal.execute(cmd).collect { line ->
                when (line.stream) {
                    TerminalStream.EXIT -> {
                        _uiState.update { it.copy(isRunning = false, currentCommand = null) }
                    }
                    else -> appendLocal(line.text, line.stream)
                }
            }
            _uiState.update { it.copy(isRunning = false, currentCommand = null) }
        }
    }

    /** Roda um comando "de fora" (ex: botões de atalho). */
    fun runCommandDirect(cmd: String) {
        if (!_uiState.value.isReady || _uiState.value.isRunning) return
        _uiState.update { it.copy(input = cmd) }
        runCommand()
    }

    fun cancel() {
        executeJob?.cancel()
        executeJob = null
        _uiState.update { it.copy(isRunning = false, currentCommand = null) }
    }

    fun clearOutput() {
        _uiState.update { it.copy(output = emptyList()) }
    }

    fun clearError() {
        _uiState.update { it.copy(prepareError = null) }
    }

    private fun appendLocal(text: String, stream: TerminalStream) {
        _uiState.update {
            val newOutput = it.output + OutputLine(stream, text)
            val capped = if (newOutput.size > 2000) newOutput.takeLast(2000) else newOutput
            it.copy(output = capped)
        }
    }
}
