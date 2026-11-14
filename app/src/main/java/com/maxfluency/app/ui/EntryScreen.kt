package com.maxfluency.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxfluency.app.AppUiState
import com.maxfluency.app.R
import com.maxfluency.core.WordEntry

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(
    state: AppUiState,
    onFavorite: (WordEntry) -> Unit,
    onSpeak: (WordEntry) -> Unit,
    onCopy: (WordEntry) -> Unit,
    onRelated: (String, WordEntry) -> Unit,
    onRetry: () -> Unit,
) {
    val entry = state.entry
    when {
        state.entryLoading -> LoadingPanel()
        entry == null -> ErrorPanel(state.error ?: R.string.error_entry, onRetry)
        else -> LazyColumn(Modifier.fillMaxWidth().testTag("entry-screen"),
            contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text(entry.language.nativeName, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SelectionContainer {
                    Text(entry.word, fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 48.sp)
                }
            }
            if (entry.pronunciation.isNotBlank()) item {
                Text(entry.pronunciation, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FavoriteButton(entry.id in state.favoriteIds, { onFavorite(entry) })
                    TextButton(onClick = { onSpeak(entry) }) { Text(stringResource(R.string.listen)) }
                    TextButton(onClick = { onCopy(entry) }) { Text(stringResource(R.string.copy)) }
                }
            }
            if (state.archivedEntry) item {
                Text(stringResource(R.string.archived_note), style = MaterialTheme.typography.bodyMedium)
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            itemsIndexed(entry.senses) { index, sense ->
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}. ${partOfSpeech(sense.partOfSpeech)}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SelectionContainer { Text(sense.definition, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            if (entry.related.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.related_words)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.related.forEach { word -> Tag(word, { onRelated(word, entry) }) }
                    }
                }
            }
        }
    }
}
