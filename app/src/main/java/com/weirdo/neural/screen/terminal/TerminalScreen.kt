package com.weirdo.neural.screen.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TerminalScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Terminal", style = MaterialTheme.typography.headlineMedium)
        Text("Em construção — Etapa 3 traz shell + Python.",
            style = MaterialTheme.typography.bodyMedium)
    }
}
