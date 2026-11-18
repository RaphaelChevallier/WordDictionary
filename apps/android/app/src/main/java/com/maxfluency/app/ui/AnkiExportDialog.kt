package com.maxfluency.app.ui

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.maxfluency.app.MaxFluencyViewModel
import com.maxfluency.app.R
import com.maxfluency.app.data.AnkiDroidExporter
import com.maxfluency.app.data.AnkiExportUi
import java.time.LocalDate
import androidx.compose.ui.unit.dp

@Composable
internal fun AnkiExportDialog(state: AnkiExportUi, viewModel: MaxFluencyViewModel) {
    val context = LocalContext.current
    var requesting by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        requesting = false
        if (uri != null) viewModel.saveAnkiDeck(uri)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requesting = false
        if (granted) viewModel.sendToAnkiDroid() else viewModel.message(R.string.anki_permission_needed)
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) viewModel.dismissAnkiExport() },
        title = { Text(pluralStringResource(R.plurals.anki_export_title, state.entries.size, state.entries.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.anki_export_description))
                if (state.failed) Text(stringResource(R.string.anki_export_failed), color = MaterialTheme.colorScheme.error)
                if (state.busy) {
                    LinearProgressIndicator()
                    Text(stringResource(R.string.anki_export_progress, state.progress, state.entries.size))
                } else {
                    TextButton(enabled = !requesting, onClick = {
                        requesting = true
                        try {
                            save.launch("MaxFluency-${LocalDate.now()}.apkg")
                        } catch (error: ActivityNotFoundException) {
                            requesting = false
                            Log.e("AnkiExport", "No document picker is available", error)
                            viewModel.message(R.string.anki_export_failed)
                        }
                    }) {
                        Text(stringResource(R.string.anki_save_deck))
                    }
                    val installed = AnkiDroidExporter.installed(context)
                    TextButton(enabled = installed && !requesting, onClick = {
                        if (AnkiDroidExporter.permitted(context)) viewModel.sendToAnkiDroid()
                        else {
                            requesting = true
                            permission.launch(AnkiDroidExporter.PERMISSION)
                        }
                    }) { Text(stringResource(R.string.anki_send_direct)) }
                    if (!installed) Text(stringResource(R.string.anki_not_installed), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { viewModel.dismissAnkiExport() }) {
                Text(stringResource(if (state.busy) R.string.cancel else R.string.done))
            }
        },
    )
}
