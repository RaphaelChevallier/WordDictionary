package com.maxfluency.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maxfluency.app.AppUiState
import com.maxfluency.app.R
import com.maxfluency.core.WordEntry

@Composable
fun LibraryScreen(state: AppUiState, onWord: (String) -> Unit, onFavorite: (WordEntry) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val words = if (tab == 0) state.saved.filter { it.favorite } else state.saved.take(100)
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.favorites, R.string.history).forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            if (words.isEmpty()) item {
                Text(stringResource(if (tab == 0) R.string.empty_favorites else R.string.empty_history),
                    modifier = Modifier.padding(vertical = 24.dp))
            }
            items(words, key = { it.entry.id }) { saved ->
                WordRow(saved.entry, { onWord(saved.entry.id) }, saved.favorite, { onFavorite(saved.entry) })
                HorizontalDivider()
            }
        }
    }
}
