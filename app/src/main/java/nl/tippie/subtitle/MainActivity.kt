package nl.tippie.subtitle

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.ui.EditorViewModel
import nl.tippie.subtitle.ui.ExportViewModel
import nl.tippie.subtitle.ui.HomeViewModel
import nl.tippie.subtitle.ui.ImportViewModel
import nl.tippie.subtitle.ui.ProcessingViewModel
import nl.tippie.subtitle.ui.SettingsViewModel
import nl.tippie.subtitle.ui.editor.EditorScreen
import nl.tippie.subtitle.ui.export.ExportScreen
import nl.tippie.subtitle.ui.home.HomeScreen
import nl.tippie.subtitle.ui.importscreen.ImportScreen
import nl.tippie.subtitle.ui.navigation.EditorRoute
import nl.tippie.subtitle.ui.navigation.ExportRoute
import nl.tippie.subtitle.ui.navigation.HomeRoute
import nl.tippie.subtitle.ui.navigation.ImportRoute
import nl.tippie.subtitle.ui.navigation.ProcessingRoute
import nl.tippie.subtitle.ui.navigation.SettingsRoute
import nl.tippie.subtitle.ui.processing.ProcessingScreen
import nl.tippie.subtitle.ui.settings.SettingsScreen
import nl.tippie.subtitle.ui.theme.SubtitleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SubtitleTheme {
                SubtitleApp()
            }
        }
    }
}

@Composable
private fun SubtitleApp() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // Foreground-service progress needs a notification on API 33+. Asked once, up front,
    // because a long job with no visible progress is worse than a permission prompt.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    NavHost(navController = navController, startDestination = HomeRoute) {

        composable<HomeRoute> {
            val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
            val projects by viewModel.projects.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    // Persist read access so a project survives an app restart.
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    navController.navigate(ImportRoute(uri.toString()))
                }
            }

            HomeScreen(
                projects = projects,
                onPickVideo = { picker.launch(arrayOf("video/*")) },
                onOpenProject = { project ->
                    navController.navigate(
                        if (project.status == ProjectStatus.COMPLETED) EditorRoute(project.id)
                        else ProcessingRoute(project.id)
                    )
                },
                onDeleteProject = viewModel::delete,
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }

        composable<ImportRoute> { entry ->
            val route = entry.toRoute<ImportRoute>()
            val viewModel: ImportViewModel = viewModel(factory = ImportViewModel.Factory)
            LaunchedEffect(route.uri) { viewModel.load(route.uri) }
            val state by viewModel.state.collectAsState()

            ImportScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSelectTrack = viewModel::selectTrack,
                onSelectLanguage = viewModel::selectLanguage,
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onStart = {
                    scope.launch {
                        viewModel.start()?.let { id ->
                            navController.navigate(ProcessingRoute(id)) {
                                popUpTo(HomeRoute)
                            }
                        }
                    }
                },
            )
        }

        composable<ProcessingRoute> { entry ->
            val projectId = entry.toRoute<ProcessingRoute>().projectId
            val viewModel: ProcessingViewModel =
                viewModel(factory = ProcessingViewModel.factory(projectId))
            val project by viewModel.project.collectAsState()
            val progress by viewModel.progress.collectAsState()
            val error by viewModel.errorMessage.collectAsState()
            val cueCount by viewModel.cueCount.collectAsState()

            ProcessingScreen(
                project = project,
                progress = progress,
                errorMessage = error,
                cueCount = cueCount,
                onBack = { navController.popBackStack() },
                onCancel = { viewModel.cancel() },
                onResume = { viewModel.resume() },
                onRetryFailed = { viewModel.retryFailed() },
                onOpenEditor = { navController.navigate(EditorRoute(projectId)) },
            )
        }

        composable<EditorRoute> { entry ->
            val projectId = entry.toRoute<EditorRoute>().projectId
            val viewModel: EditorViewModel = viewModel(factory = EditorViewModel.factory(projectId))
            val state by viewModel.state.collectAsState()

            EditorScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onQueryChange = viewModel::setQuery,
                onSelectCue = viewModel::select,
                onEditCue = viewModel::edit,
                onDeleteCue = viewModel::delete,
                onSplitCue = viewModel::split,
                onMergeWithNext = viewModel::mergeWithNext,
                onAddCue = viewModel::addCue,
                onShiftAll = viewModel::shiftAll,
                onOpenShiftDialog = viewModel::openShiftDialog,
                onPositionChange = viewModel::onPosition,
                onSeek = viewModel::seek,
                onExport = { navController.navigate(ExportRoute(projectId)) },
            )
        }

        composable<ExportRoute> { entry ->
            val projectId = entry.toRoute<ExportRoute>().projectId
            val viewModel: ExportViewModel = viewModel(factory = ExportViewModel.factory(projectId))
            val state by viewModel.ui.collectAsState()

            val subtitlePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(state.format.mimeType)
            ) { uri -> uri?.let(viewModel::export) }

            val videoPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("video/mp4")
            ) { uri -> uri?.let(viewModel::burnIn) }

            ExportScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSelectFormat = viewModel::setFormat,
                onPickDestination = { subtitlePicker.launch(viewModel.suggestedName()) },
                onBurnIn = { videoPicker.launch("${state.projectName.substringBeforeLast('.')}-subtitled.mp4") },
                onOpenStyle = { navController.navigate(SettingsRoute) },
            )
        }

        composable<SettingsRoute> {
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
            val state by viewModel.ui.collectAsState()

            SettingsScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSetApiKey = viewModel::setApiKey,
                onClearApiKey = viewModel::clearApiKey,
                onValidateKey = { viewModel.validateKey() },
                onSetProvider = viewModel::setProvider,
                onSetLanguage = viewModel::setLanguage,
                onSetChunkMinutes = viewModel::setChunkMinutes,
                onSetEncoding = viewModel::setEncoding,
                onSetParallel = viewModel::setParallel,
                onSetRequireUnmetered = viewModel::setRequireUnmetered,
                onSetStyle = viewModel::setStyle,
                onClearCache = { viewModel.clearCache() },
            )
        }
    }
}
