package com.maxfluency.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maxfluency.app.AppUiState
import com.maxfluency.app.R

@Composable
fun AssessmentScreen(state: AppUiState, onAnswer: (String?) -> Unit, onRetry: () -> Unit, onDone: () -> Unit) {
    val words = state.assessment
    val question = words.getOrNull(state.assessmentIndex)
    LazyColumn(Modifier.testTag("assessment-screen"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SectionTitle(stringResource(R.string.assessment_title)) }
        item { Text(stringResource(R.string.assessment_note), style = MaterialTheme.typography.bodyMedium) }
        when {
            state.error != null -> item { ErrorPanel(state.error, onRetry) }
            words.isEmpty() -> item { LoadingPanel() }
            question == null -> {
                item { Text(stringResource(R.string.assessment_result, state.assessmentCorrect, words.size)) }
                item { Text(stringResource(R.string.assessment_saved, words.first().language.nativeName)) }
                item { OutlinedButton(onClick = onDone) { Text(stringResource(R.string.done)) } }
            }
            else -> {
                item { Text(stringResource(R.string.assessment_progress, state.assessmentIndex + 1, words.size)) }
                item { Text(stringResource(R.string.assessment_question), style = MaterialTheme.typography.titleMedium) }
                item { Text(question.senses.first().definition, style = MaterialTheme.typography.bodyLarge) }
                val wrong = words.filter {
                    it.id != question.id && it.word !in question.related && question.word !in it.related &&
                        it.senses.first().definition != question.senses.first().definition
                }.take(2)
                val choices = (wrong + question).sortedBy { "${question.id}:${it.id}".hashCode() }
                choices.forEach { choice ->
                    item { OutlinedButton(onClick = { onAnswer(choice.id) }, enabled = !state.answering,
                        modifier = Modifier.fillMaxWidth()) { Text(choice.word) } }
                }
                item { TextButton(onClick = { onAnswer(null) }, enabled = !state.answering) { Text(stringResource(R.string.assessment_skip)) } }
            }
        }
    }
}
