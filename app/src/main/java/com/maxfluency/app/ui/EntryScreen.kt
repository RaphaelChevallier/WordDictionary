package com.maxfluency.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxfluency.app.AppUiState
import com.maxfluency.app.R
import com.maxfluency.core.Language
import com.maxfluency.core.WordEntry
import com.maxfluency.app.data.DefinitionTranslationPhase
import com.maxfluency.app.data.definitionTranslationTarget
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(
    state: AppUiState,
    onFavorite: (WordEntry) -> Unit,
    onSpeak: (WordEntry) -> Unit,
    onCopy: (WordEntry) -> Unit,
    onRelated: (String, WordEntry) -> Unit,
    onRetry: () -> Unit,
    onTranslate: () -> Unit = {},
    onDownloadTranslation: () -> Unit = {},
    onHideTranslation: () -> Unit = {},
    onAnkiExport: (WordEntry) -> Unit = {},
) {
    val entry = state.entry
    var menuExpanded by remember(entry?.id) { mutableStateOf(false) }
    val target = entry?.let { definitionTranslationTarget(it.language.code, state.systemLanguageTag) }
    val translation = state.translation?.takeIf { it.entryId == entry?.id && it.targetTag == target }
    val showingTranslation = translation?.phase == DefinitionTranslationPhase.READY && translation.expanded
    // Cover the outgoing destination while navigation finishes removing it.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when {
            state.entryLoading -> LoadingPanel()
            entry == null -> ErrorPanel(state.error ?: R.string.error_entry, onRetry)
            else -> LazyColumn(Modifier.fillMaxSize().testTag("entry-screen"),
                contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    val locale = LocalConfiguration.current.locales[0]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.language.nativeName, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.entryArchive?.let {
                            Text(stringResource(R.string.word_of_day_date, it.date.format(
                                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("archived-word-date"))
                            if (it.backfilled) Text(stringResource(R.string.calendar_assigned_day),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    SelectionContainer {
                        Text(entry.word, fontFamily = FontFamily.Serif, fontSize = 42.sp, lineHeight = 48.sp)
                    }
                }
                if (entry.language == Language.ENGLISH && entry.pronunciationGuide.isNotBlank()) item {
                    PronunciationGuide(entry)
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FavoriteButton(entry.id in state.favoriteIds, { onFavorite(entry) })
                        TextButton(onClick = { onSpeak(entry) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.listen))
                        }
                        TextButton(onClick = { onCopy(entry) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.copy))
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.more_word_actions))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.anki_export)) },
                                    enabled = state.ankiExport?.busy != true,
                                    onClick = {
                                        menuExpanded = false
                                        onAnkiExport(entry)
                                    },
                                )
                            }
                        }
                    }
                }
                if (state.archivedEntry) item {
                    Text(stringResource(R.string.archived_note), style = MaterialTheme.typography.bodyMedium)
                }
                if (target != null) item {
                    DefinitionTranslationPanel(target, translation, onTranslate, onDownloadTranslation, onHideTranslation)
                }
                if (showingTranslation || entry.senses.size > 1) item {
                    if (showingTranslation) {
                        SectionTitle(stringResource(R.string.original_definition))
                    } else if (entry.senses.size > 1) {
                        SectionTitle(pluralStringResource(R.plurals.meaning_count, entry.senses.size, entry.senses.size))
                    }
                }
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                itemsIndexed(entry.senses) { index, sense ->
                    Column(Modifier.testTag("original-sense-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${partOfSpeech(sense.partOfSpeech)}",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(sense.definition, style = MaterialTheme.typography.bodyLarge) }
                        UsageExamples(sense.examples)
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
}

@Composable
internal fun UsageExamples(examples: List<String>) {
    if (examples.isNotEmpty()) {
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.usage_examples), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            examples.forEach { example ->
                SelectionContainer {
                    Text(example, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}
