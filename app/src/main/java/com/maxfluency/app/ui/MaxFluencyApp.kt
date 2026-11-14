package com.maxfluency.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maxfluency.app.AppUiState
import com.maxfluency.app.MaxFluencyViewModel
import com.maxfluency.app.OfflineSpeech
import com.maxfluency.app.R
import com.maxfluency.app.data.HomeView
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun AppNavigationBar(route: String, onNavigate: (String) -> Unit, homeView: HomeView = HomeView.GARDEN) {
    NavigationBar(
        modifier = Modifier.testTag("bottom-navigation"),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        val alternate = when (homeView) {
            HomeView.GARDEN -> Triple("alternate", R.string.home_word_of_day, Icons.Default.DateRange)
            HomeView.WORD_OF_DAY -> Triple("alternate", R.string.home_word_garden, Icons.AutoMirrored.Filled.List)
        }
        val destinations = listOf(
            Triple("today", R.string.today, Icons.Default.Home),
            alternate,
            Triple("library", R.string.library, Icons.Default.Star),
            Triple("settings", R.string.settings, Icons.Default.Settings),
        )
        destinations.forEach { (destination, label, icon) ->
            NavigationBarItem(selected = route == destination, onClick = { onNavigate(destination) },
                modifier = Modifier.testTag("nav-$destination"),
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaxFluencyApp(state: AppUiState, viewModel: MaxFluencyViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val nav = rememberNavController()
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route ?: "today"
    val roots = listOf("today", "alternate", "library", "settings")
    val snackbar = remember { SnackbarHostState() }
    val speech = remember(context) { OfflineSpeech(context, viewModel::message) }
    DisposableEffect(speech) { onDispose { speech.close() } }

    LaunchedEffect(viewModel, context) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(if (message.argument == null) context.getString(message.resource)
                else context.getString(message.resource, message.argument))
        }
    }
    LaunchedEffect(lifecycle, viewModel, state.daily?.date) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel.refreshDayIfNeeded()
                val now = ZonedDateTime.now()
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000))
            }
        }
    }

    fun root(destination: String) {
        nav.navigate(destination) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun entry(id: String) {
        viewModel.openEntry(id)
        nav.navigate("entry/${Uri.encode(id)}") { launchSingleTop = true }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    if (route !in roots) IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            if (route in roots) AppNavigationBar(route, onNavigate = { destination ->
                if (destination != route && (destination == "today" || destination == "alternate")) {
                    viewModel.dismissSearch()
                }
                root(destination)
            }, homeView = state.settings.homeView)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            NavHost(nav, startDestination = "today", modifier = Modifier.widthIn(max = 840.dp).fillMaxHeight(),
                enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
                composable("today") {
                    TodayScreen(state, ::entry, viewModel::query, viewModel::language, viewModel::mode, viewModel::retry,
                        viewModel::searchOnline, viewModel::refreshGarden, speech::speak)
                }
                composable("alternate") {
                    TodayScreen(state, ::entry, viewModel::query, viewModel::language, viewModel::mode, viewModel::retry,
                        viewModel::searchOnline, viewModel::refreshGarden, speech::speak,
                        homeView = if (state.settings.homeView == HomeView.GARDEN) HomeView.WORD_OF_DAY else HomeView.GARDEN)
                }
                composable("library") {
                    LibraryScreen(state, ::entry, viewModel::toggleFavorite)
                }
                composable("settings") {
                    SettingsScreen(state, viewModel, { nav.navigate("credits") }, {
                        viewModel.startAssessment()
                        nav.navigate("assessment")
                    })
                }
                composable("assessment") {
                    AssessmentScreen(state, viewModel::answerAssessment, viewModel::startAssessment, { nav.popBackStack() })
                }
                composable("credits") {
                    CreditsScreen(state, onSource = { url ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (error: ActivityNotFoundException) {
                            Log.e("MaxFluency", "No browser can open the dictionary attribution", error)
                            viewModel.message(R.string.error_source)
                        }
                    })
                }
                composable("entry/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
                    val id = backStack.arguments?.getString("id") ?: error("Missing internal word navigation ID")
                    LaunchedEffect(id) {
                        if (viewModel.state.value.entryId != id) viewModel.openEntry(id)
                    }
                    EntryScreen(
                        state = state,
                        onFavorite = viewModel::toggleFavorite,
                        onSpeak = speech::speak,
                        onCopy = { word ->
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            if (clipboard == null) {
                                Log.e("MaxFluency", "Clipboard service is unavailable")
                                viewModel.message(R.string.error_copy)
                            } else {
                                val text = word.word + "\n\n" + word.senses.joinToString("\n\n") { it.definition } +
                                    "\n\n" + word.sourceLinks.joinToString("\n") { it.second } +
                                    "\nhttps://creativecommons.org/licenses/by-sa/4.0/"
                                clipboard.setPrimaryClip(ClipData.newPlainText(word.word, text))
                                viewModel.message(R.string.copied)
                            }
                        },
                        onRelated = { word, source ->
                            viewModel.relatedWord(word, source.language)
                            root("today")
                        },
                        onRetry = { viewModel.retryEntry(id) },
                    )
                }
            }

        }
    }
}
