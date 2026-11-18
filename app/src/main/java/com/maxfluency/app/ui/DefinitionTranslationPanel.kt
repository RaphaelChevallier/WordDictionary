package com.maxfluency.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import com.maxfluency.app.R
import com.maxfluency.app.data.DefinitionTranslationPhase
import com.maxfluency.app.data.DefinitionTranslationProvider
import com.maxfluency.app.data.DefinitionTranslationUi
import java.util.Locale

@Composable
internal fun DefinitionTranslationPanel(
    targetTag: String,
    state: DefinitionTranslationUi?,
    onTranslate: () -> Unit,
    onDownload: () -> Unit,
    onHide: () -> Unit,
) {
    val targetName = Locale.forLanguageTag(targetTag).getDisplayLanguage(LocalConfiguration.current.locales[0])
    val phase = state?.phase ?: DefinitionTranslationPhase.CHECKING
    val expanded = phase == DefinitionTranslationPhase.READY && state?.expanded == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (phase == DefinitionTranslationPhase.UNSUPPORTED) {
            Text(stringResource(R.string.translation_unsupported, targetName),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(
                onClick = if (expanded) onHide else onTranslate,
                enabled = phase != DefinitionTranslationPhase.CHECKING && phase != DefinitionTranslationPhase.TRANSLATING,
                modifier = Modifier.testTag("show-translated-definition"),
            ) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (phase == DefinitionTranslationPhase.CHECKING || phase == DefinitionTranslationPhase.TRANSLATING) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Text(stringResource(when (phase) {
                            DefinitionTranslationPhase.CHECKING -> R.string.checking_definition_translation
                            DefinitionTranslationPhase.TRANSLATING -> R.string.preparing_definition_translation
                            else -> if (expanded) R.string.hide_definition_translation else R.string.show_definition_translation
                        }, targetName))
                    }
                    if (!expanded && state?.provider == DefinitionTranslationProvider.GOOGLE) {
                        Text(stringResource(R.string.translate_with_google), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (phase == DefinitionTranslationPhase.FAILED) {
            Text(stringResource(R.string.translation_failed), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Column(Modifier.testTag("translated-definitions"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle(stringResource(R.string.translated_definition_title, targetName))
                Text(stringResource(R.string.machine_translation_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.content?.let { translated ->
                    SelectionContainer {
                        Text(translated.word, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif,
                            modifier = Modifier.testTag("translated-headword"))
                    }
                    translated.senses.forEachIndexed { index, sense ->
                        Column(Modifier.testTag("translated-sense-$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectionContainer { Text("${index + 1}. ${sense.definition}", style = MaterialTheme.typography.bodyLarge) }
                            UsageExamples(sense.examples)
                        }
                    }
                }
                if (state.provider == DefinitionTranslationProvider.GOOGLE) {
                    Image(painterResource(R.drawable.google_translate_attribution),
                        contentDescription = stringResource(R.string.google_translation_attribution),
                        modifier = Modifier.height(40.dp).testTag("translation-provider-credit"))
                }
            }
        }
    }
    if (phase == DefinitionTranslationPhase.MODEL_REQUIRED) {
        AlertDialog(
            onDismissRequest = onHide,
            title = { Text(stringResource(R.string.translation_download_title)) },
            text = { Text(stringResource(R.string.translation_download_message)) },
            confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.translate_with_google)) } },
            dismissButton = { TextButton(onClick = onHide) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
