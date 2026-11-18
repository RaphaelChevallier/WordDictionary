package com.maxfluency.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maxfluency.app.R
import com.maxfluency.app.data.ArchivedDailyWord
import com.maxfluency.app.data.VocabularyLevel
import com.maxfluency.app.data.WordCalendarUi
import com.maxfluency.core.Language
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields

@Composable
fun WordCalendarScreen(
    state: WordCalendarUi?,
    onMonth: (YearMonth) -> Unit,
    onLanguage: (Language) -> Unit,
    onLevel: (VocabularyLevel) -> Unit,
    onToday: () -> Unit,
    onWord: (ArchivedDailyWord) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("calendar-screen").verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state == null) {
            LoadingPanel()
        } else {
            val locale = LocalConfiguration.current.locales[0]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu(state.language, Language.entries.map { it to it.nativeName }, onLanguage,
                    modifier = Modifier.weight(1f).testTag("calendar-language"))
                ChoiceMenu(state.level, listOf(
                    VocabularyLevel.BEGINNER to stringResource(R.string.level_beginner),
                    VocabularyLevel.INTERMEDIATE to stringResource(R.string.level_intermediate),
                    VocabularyLevel.ADVANCED to stringResource(R.string.level_advanced)), onLevel,
                    modifier = Modifier.weight(1f).testTag("calendar-level"))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMonth(state.month.minusMonths(1)) },
                    enabled = state.month > YearMonth.of(1, 1),
                    modifier = Modifier.testTag("calendar-previous-month")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.previous_month))
                }
                Text(state.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)),
                    modifier = Modifier.weight(1f).testTag("calendar-month"),
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { onMonth(state.month.plusMonths(1)) },
                    enabled = state.month < YearMonth.from(state.today),
                    modifier = Modifier.testTag("calendar-next-month")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.next_month))
                }
            }
            TextButton(onClick = onToday, modifier = Modifier.align(Alignment.End).testTag("calendar-today")) {
                Text(stringResource(R.string.today))
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("calendar-loading"))
            if (state.failed) ErrorPanel(R.string.error_calendar, onRetry)
            val firstWeekday = WeekFields.of(locale).firstDayOfWeek
            val first = state.month.atDay(1)
            val offset = Math.floorMod(first.dayOfWeek.value - firstWeekday.value, 7)
            val rows = (offset + state.month.lengthOfMonth() + 6) / 7
            val words = state.words.associateBy { it.date }
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gridWidth = maxOf(maxWidth, 348.dp)
                Column(Modifier.horizontalScroll(rememberScrollState()).testTag("calendar-grid")) {
                    Column(Modifier.width(gridWidth), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(7) { index ->
                                Text(firstWeekday.plus(index.toLong()).getDisplayName(TextStyle.SHORT, locale),
                                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        repeat(rows) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                repeat(7) { column ->
                                    val day = row * 7 + column - offset + 1
                                    if (day !in 1..state.month.lengthOfMonth()) {
                                        Column(Modifier.weight(1f).heightIn(min = 84.dp)) {}
                                    } else {
                                        val date = state.month.atDay(day)
                                        val word = words[date]?.takeIf { date <= state.today }
                                        val selectable = date <= state.today && word != null && !state.failed
                                        val highlighted = date == (state.selectedDate ?: state.today)
                                        Surface(
                                            onClick = { if (word != null) onWord(word) },
                                            enabled = selectable,
                                            modifier = Modifier.weight(1f).heightIn(min = 84.dp)
                                                .testTag("calendar-day-$date").semantics {
                                                    selected = highlighted
                                                    contentDescription = listOfNotNull(date.format(formatter), word?.entry?.word)
                                                        .joinToString(", ")
                                                },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outlineVariant),
                                            color = MaterialTheme.colorScheme.surface,
                                        ) {
                                            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(day.toString(), style = MaterialTheme.typography.labelMedium,
                                                    color = if (date > state.today) MaterialTheme.colorScheme.outline
                                                        else MaterialTheme.colorScheme.onSurface)
                                                word?.let {
                                                    Text(it.entry.word, fontFamily = FontFamily.Serif,
                                                        style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                                                        overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.calendar_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
