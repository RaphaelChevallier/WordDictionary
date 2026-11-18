package com.maxfluency.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maxfluency.app.DownloadUi
import com.maxfluency.app.R
import com.maxfluency.app.data.PublicDictionaries
import com.maxfluency.core.Language
import java.util.Locale

@Composable
fun FullDictionaryCard(language: Language, state: DownloadUi,
    onDownload: (Language) -> Unit, onCancel: (Language) -> Unit, onRemove: ((Language) -> Unit)? = null,
    compact: Boolean = false) {
    var confirm by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    val size = String.format(Locale.ROOT, "%.1f", PublicDictionaries.estimatedBytes(language) / 1e6)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (compact) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(if (compact) 0.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!compact) Text(language.nativeName, style = MaterialTheme.typography.titleMedium)
            when {
                state.active -> {
                    Text(stringResource(if (state.preparing) R.string.preparing_dictionary else R.string.downloading_dictionary, state.percent))
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { onCancel(language) }) { Text(stringResource(R.string.cancel_download)) }
                }
                state.info != null -> {
                    Text(stringResource(R.string.dictionary_installed, state.info.entries))
                    if (onRemove != null) TextButton(onClick = { remove = true }) { Text(stringResource(R.string.remove_dictionary)) }
                }
                else -> {
                    if (!compact) {
                        Text(stringResource(R.string.download_size, size), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.failed) Text(stringResource(R.string.download_failed), color = MaterialTheme.colorScheme.error)
                    if (compact) {
                        TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.download_dictionary)) }
                    } else {
                        OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.download_dictionary)) }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.confirm_download, language.nativeName)) },
        text = { Text(stringResource(R.string.download_size, size)) },
        confirmButton = { TextButton(onClick = { confirm = false; onDownload(language) }) { Text(stringResource(R.string.download_action)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (remove) AlertDialog(
        onDismissRequest = { remove = false },
        title = { Text(stringResource(R.string.remove_dictionary)) },
        text = { Text(stringResource(R.string.remove_dictionary_note)) },
        confirmButton = { TextButton(onClick = { remove = false; onRemove?.invoke(language) }) { Text(stringResource(R.string.remove_dictionary)) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
