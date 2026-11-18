package com.maxfluency.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import com.maxfluency.app.data.HomeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxfluency.app.AppUiState
import com.maxfluency.app.R
import com.maxfluency.core.Language
import com.maxfluency.core.WordEntry
import com.maxfluency.core.DailyWords
import com.maxfluency.core.SearchMode
import com.maxfluency.core.SearchPlan
import com.maxfluency.core.dailyGridSize
import com.maxfluency.core.searchPlan
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
private fun CompactSearchField(
    query: String,
    focused: Boolean,
    onQuery: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.testTag("search-field").onFocusChanged { onFocus(it.isFocused) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { input ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(stringResource(R.string.search_hint),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear_search))
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(
    state: AppUiState,
    onWord: (String) -> Unit,
    onQuery: (String) -> Unit,
    onLanguage: (Language) -> Unit,
    onMode: (SearchMode) -> Unit,
    onRetry: () -> Unit,
    onOnlineSearch: () -> Unit,
    onRefresh: (Set<String>) -> Unit,
    onSpeak: (WordEntry) -> Unit,
    homeView: HomeView = state.settings.homeView,
) {
    var searchFocused by remember { mutableStateOf(false) }
    val searchActive = searchFocused || state.query.isNotBlank()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ChoiceMenu(state.settings.language, Language.entries.map { it to it.nativeName }, onLanguage,
                    modifier = Modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
                    selectedLabel = if (searchActive) state.settings.language.code.uppercase(Locale.ROOT) else null)
                CompactSearchField(state.query, searchFocused, onQuery, { searchFocused = it },
                    onOnlineSearch, Modifier.weight(1f))
            }
            if (searchFocused || state.query.isNotBlank() || state.mode != SearchMode.WORDS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.mode == SearchMode.WORDS,
                        onClick = { onMode(SearchMode.WORDS) }, label = { Text(stringResource(R.string.search_words)) })
                    FilterChip(selected = state.mode == SearchMode.ANAGRAMS,
                        onClick = { onMode(SearchMode.ANAGRAMS) }, label = { Text(stringResource(R.string.search_anagrams)) })
                }
                Text(stringResource(if (state.mode == SearchMode.WORDS) R.string.search_help else R.string.anagram_help),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.query.isNotBlank() && state.mode == SearchMode.WORDS) {
                TextButton(onClick = onOnlineSearch, enabled = !state.searching) {
                    Text(stringResource(R.string.online_lookup))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gardenHeight = (maxHeight - 64.dp).coerceAtLeast(48.dp)
            val gridSize = dailyGridSize(maxWidth.value, LocalDensity.current.fontScale, gardenHeight.value)
            LazyColumn(Modifier.fillMaxSize().testTag("today-screen"),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                when {
                    state.error != null -> item { ErrorPanel(state.error, onRetry) }
                    state.loading && state.daily == null -> item { LoadingPanel() }
                    state.query.isNotBlank() -> {
                        when {
                            searchPlan(state.query, state.mode) == SearchPlan.NoLetters ->
                                item { Text(stringResource(R.string.no_letters), color = MaterialTheme.colorScheme.error) }
                            state.searching -> item { CircularProgressIndicator() }
                            state.matches.isEmpty() -> item { Text(stringResource(R.string.no_results)) }
                            else -> {
                                items(state.matches, key = { it.id }) { entry -> WordRow(entry, { onWord(entry.id) }) }
                                if (state.matches.size == 60) item {
                                    Text(stringResource(R.string.search_limit), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    else -> state.daily?.let { daily ->
                        if (homeView == HomeView.GARDEN) {
                            val garden = daily.grid.take(gridSize)
                            item {
                                key(daily.date, state.settings.language, state.settings.levels.getValue(state.settings.language), state.loading) {
                                    WordGarden(garden, onWord, gardenHeight, refreshRound = daily.round)
                                }
                            }
                            if (garden.isNotEmpty()) item {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    TextButton(
                                        onClick = { onRefresh(garden.map { it.id }.toSet()) },
                                        enabled = !state.loading && !state.refreshingGarden,
                                        modifier = Modifier.testTag("refresh-garden"),
                                    ) {
                                        if (state.refreshingGarden) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text(stringResource(if (state.refreshingGarden) R.string.garden_refreshing else R.string.garden_refresh),
                                            modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                        } else item {
                            FeaturedWordCard(daily, onWord, onSpeak)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeaturedWordCard(daily: DailyWords, onWord: (String) -> Unit, onSpeak: (WordEntry) -> Unit) {
    val configuration = LocalConfiguration.current
    Surface(
        onClick = { onWord(daily.featured.id) },
        modifier = Modifier.fillMaxWidth().testTag("word-of-day"),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.word_of_day), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp)
                Text(daily.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                    .withLocale(configuration.locales[0])), style = MaterialTheme.typography.bodySmall)
            }
            Text(daily.featured.word, style = MaterialTheme.typography.headlineLarge)
            PronunciationGuide(daily.featured)
            TextButton(onClick = { onSpeak(daily.featured) }) { Text(stringResource(R.string.listen_device_voice)) }
            daily.featured.senses.firstOrNull()?.let {
                Text(it.definition, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            val additionalMeanings = daily.featuredMeaningCount - 1
            if (additionalMeanings > 0) {
                Row(Modifier.testTag("more-meanings"), horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.more_meanings, additionalMeanings, additionalMeanings),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
