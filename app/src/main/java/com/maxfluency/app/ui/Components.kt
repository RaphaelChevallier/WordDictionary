package com.maxfluency.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxfluency.app.R
import com.maxfluency.core.WordEntry
import java.util.Locale

@Composable
fun LoadingPanel() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.loading_dictionary), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorPanel(message: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
fun <T> ChoiceMenu(value: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, selectedLabel: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    val fullLabel = choices.first { it.first == value }.second
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true },
            contentPadding = if (selectedLabel == null) ButtonDefaults.ContentPadding else PaddingValues(horizontal = 10.dp)) {
            Text(selectedLabel ?: fullLabel,
                modifier = if (selectedLabel == null) Modifier else Modifier.clearAndSetSemantics { text = AnnotatedString(fullLabel) })
            Spacer(Modifier.width(if (selectedLabel == null) 6.dp else 4.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (choice, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    onSelect(choice)
                })
            }
        }
    }
}

@Composable
fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(if (favorite) Icons.Default.Star else Icons.Outlined.Star,
            contentDescription = stringResource(if (favorite) R.string.unfavorite else R.string.favorite),
            tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun WordRow(entry: WordEntry, onClick: () -> Unit, favorite: Boolean? = null, onFavorite: (() -> Unit)? = null) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("word-row-${entry.id}"),
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.word, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                    Text(entry.language.code.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                entry.senses.firstOrNull()?.let { sense ->
                    Text(sense.definition, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (favorite != null && onFavorite != null) FavoriteButton(favorite, onFavorite)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Tag(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun partOfSpeech(part: String): String = if (part.startsWith("other:")) part.substringAfter(':') else stringResource(when (part) {
    "noun" -> R.string.pos_noun
    "verb" -> R.string.pos_verb
    "adj" -> R.string.pos_adj
    "adv" -> R.string.pos_adv
    "pron" -> R.string.pos_pron
    "det" -> R.string.pos_det
    "prep" -> R.string.pos_prep
    "conj" -> R.string.pos_conj
    "intj" -> R.string.pos_intj
    "num" -> R.string.pos_num
    "proverb" -> R.string.pos_proverb
    "contraction" -> R.string.pos_contraction
    "prefix" -> R.string.pos_prefix
    "suffix" -> R.string.pos_suffix
    "affix" -> R.string.pos_affix
    "particle" -> R.string.pos_particle
    "article" -> R.string.pos_article
    else -> R.string.pos_phrase
})
