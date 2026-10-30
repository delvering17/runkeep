package com.runkeep.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runkeep.app.RunkeepApp
import com.runkeep.app.tracking.RunService
import com.runkeep.core.*
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RunkeepRoot(app: RunkeepApp, permissionRationale: Boolean) {
    val context = LocalContext.current
    val dao = app.db.dao()
    val courseRows by remember(dao) { dao.courses() }.collectAsStateWithLifecycle(emptyList())
    val planRows by remember(dao) { dao.plans() }.collectAsStateWithLifecycle(emptyList())
    val heartRunIds by remember(dao) { dao.heartRunIds() }.collectAsStateWithLifecycle(emptyList())
    val runRows by remember(dao) { dao.runs() }.collectAsStateWithLifecycle(emptyList())
    val courses = remember(courseRows) { courseRows.map { codec.decodeFromString<Course>(it.payload) } }
    val plans = remember(planRows) { planRows.map { codec.decodeFromString<WorkoutPlan>(it.payload) } }
    val runs = remember(runRows) { runRows.map { codec.decodeFromString<RunSummary>(it.payload) } }
    val live by app.live.collectAsStateWithLifecycle()
    val prefs by app.settings.collectAsStateWithLifecycle()
    val error by app.error.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (permissionRationale) "privacy" else "run") }
    var pendingStart by rememberSavedOptionalCourseDraft { null }
    val snackbar = remember { SnackbarHostState() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val payload = pendingStart; pendingStart = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && payload != null) {
            RunService.send(context, RunService.START, codec.decodeFromString<RunSummary>(payload))
        } else app.error.value = "러닝 기록에는 정확한 위치 권한이 필요합니다."
    }
    fun start(summary: RunSummary) {
        pendingStart = codec.encodeToString(summary)
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(required.toTypedArray())
    }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); app.error.value = null } }
    val rootTabs = listOf("run", "courses", "history", "settings")
    fun back() { screen = when { screen.startsWith("course") -> "courses"; screen.startsWith("detail") -> "history"; screen in listOf("privacy", "diagnostics", "voice", "simulation") -> "settings"; else -> "run" } }
    val window = mobileWindow()
    val draftExit = remember { DraftExitState() }
    val savedScreens = rememberSaveableStateHolder()
    val labels = listOf("달리기", "코스 · 페이스", "기록", "설정")
    val icons = listOf(Icons.AutoMirrored.Outlined.DirectionsRun, Icons.Outlined.Route, Icons.Outlined.BarChart, Icons.Outlined.Tune)
    fun requestBack() { draftExit.request { back() } }
    BackHandler(enabled = screen != "run" && !(screen.startsWith("course:") || screen.startsWith("coursepace:"))) { requestBack() }
    CompositionLocalProvider(LocalDraftExit provides draftExit) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!(screen.startsWith("course:") || screen.startsWith("coursepace:"))) TopAppBar(
                    title = { Text(when {
                        screen == "plans" -> "페이스 계획"
                        screen == "diagnostics" -> "현재 위치 확인"
                        screen == "voice" -> "음성 · 경보"
                        screen == "simulation" -> "GPS 테스트"
                        screen == "privacy" -> "데이터 · 권한"
                        screen.startsWith("detail:") -> "달리기 기록"
                        else -> labels.getOrNull(rootTabs.indexOf(screen)) ?: "런킵"
                    }) },
                    navigationIcon = { if (screen !in rootTabs) IconButton(onClick = ::requestBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") } })
            },
            bottomBar = {
                if (screen in rootTabs && !window.useRail) NavigationBar {
                    rootTabs.forEachIndexed { i, id -> NavigationBarItem(selected = screen == id, onClick = { draftExit.request { screen = id } }, icon = { Icon(icons[i], null) }, label = { Text(labels[i]) }) }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                if (screen in rootTabs && window.useRail) NavigationRail {
                    rootTabs.forEachIndexed { i, id -> NavigationRailItem(selected = screen == id, onClick = { draftExit.request { screen = id } }, icon = { Icon(icons[i], null) }, label = { Text(labels[i]) }) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    savedScreens.SaveableStateProvider(screen) {
                        when {
                            screen == "run" -> RunScreen(live, runs.firstOrNull { it.status != RunStatus.FINISHED }, courses, plans, prefs,
                                onStart = ::start, onPlans = { screen = "courses" }, onDetail = { screen = "detail:$it" },
                                onCommand = { action -> RunService.send(context, action) },
                                recentRun = runs.filter { it.status == RunStatus.FINISHED }.maxByOrNull { it.startedAt },
                                onSettings = { screen = "settings" })
                            screen == "courses" -> CoursesScreen(app, courses, { id, pace -> screen = "${if (pace) "coursepace" else "course"}:$id" }, onPlans = { screen = "plans" }, runs = runs)
                            (screen.startsWith("course:") || screen.startsWith("coursepace:")) -> CourseEditor(app, courses.find { it.id == screen.substringAfter(':') }, plans, startWithPace = screen.startsWith("coursepace:"), onSaved = { savedScreens.removeState(screen); screen = "courses" })
                            screen == "plans" -> PlansScreen(app, plans)
                            screen == "history" -> HistoryScreen(runs, { screen = "detail:$it" }, heartRunIds.toSet())
                            screen.startsWith("detail:") -> runs.find { it.id == screen.substringAfter(':') }?.let { RunDetail(app, it) { screen = "history" } }
                            screen == "settings" -> SettingsScreen(app, prefs, onDiagnostics = { screen = "diagnostics" },
                                onVoice = { screen = "voice" }, onSimulation = { screen = "simulation" }, onPrivacy = { screen = "privacy" })
                            screen == "diagnostics" -> DiagnosticsScreen(app, prefs)
                            screen == "voice" -> AudioSettingsScreen(app, prefs)
                            screen == "simulation" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { GpsSimulatorPanel(app) }
                            screen == "privacy" -> PrivacyScreen()
                        }
                    }
                }
            }
        }
    }
}
