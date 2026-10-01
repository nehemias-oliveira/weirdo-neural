package com.weirdo.neural.screen.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Chat", style = MaterialTheme.typography.headlineMedium)
        Text("Em construção — Etapa 2 traz o motor de inferência.",
            style = MaterialTheme.typography.bodyMedium)
    }
}
