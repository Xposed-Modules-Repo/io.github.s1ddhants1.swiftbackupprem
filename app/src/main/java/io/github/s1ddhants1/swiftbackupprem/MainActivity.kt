package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.s1ddhants1.swiftbackupprem.ui.MainUiEvent
import io.github.s1ddhants1.swiftbackupprem.ui.MainViewModel
import io.github.s1ddhants1.swiftbackupprem.ui.component.AboutScreen
import io.github.s1ddhants1.swiftbackupprem.ui.component.AdvancedSettingsCard
import io.github.s1ddhants1.swiftbackupprem.ui.component.GuidedSetupWizard
import io.github.s1ddhants1.swiftbackupprem.ui.component.SettingsSwitch
import io.github.s1ddhants1.swiftbackupprem.ui.theme.Theme
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import kotlinx.coroutines.launch

enum class AppScreen { Settings, About }

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        makeWorldReadable()

        setContent {
            val context = LocalContext.current
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val localPrefs = remember {
                try {
                    @Suppress("DEPRECATION")
                    getSharedPreferences(Consts.PREFS_SETTINGS, Context.MODE_WORLD_READABLE)
                } catch (_: Throwable) {
                    getSharedPreferences(Consts.PREFS_SETTINGS, Context.MODE_PRIVATE)
                }
            }
            val prefsState = remember { mutableStateOf(PreferencesManager(localPrefs)) }
            val prefs = prefsState.value

            LaunchedEffect(Unit) {
                if (App.isModuleActive()) {
                    viewModel.onFrameworkConnected("Xposed", "Legacy (API 82)")
                }
            }

            var currentScreen by remember { mutableStateOf(AppScreen.Settings) }
            var showMenu by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()

            BackHandler(enabled = currentScreen != AppScreen.Settings) { currentScreen = AppScreen.Settings }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    val msg = when (event) {
                        is MainUiEvent.ConfigExported ->
                            if (event.success) getString(R.string.msg_config_exported)
                            else getString(R.string.msg_export_failed, event.error ?: "unknown error")
                        is MainUiEvent.ConfigImported ->
                            if (event.success) getString(R.string.msg_config_imported)
                            else getString(R.string.msg_import_failed, event.error ?: "unknown error")
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            }

            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) viewModel.exportConfig(contentResolver, uri, prefs)
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) viewModel.importConfig(contentResolver, uri, prefs)
            }

            Theme {
                val isImeVisible = WindowInsets.isImeVisible

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (currentScreen == AppScreen.Settings) {
                                        Icon(painter = painterResource(id = R.drawable.ic_app_bolt), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                    }
                                    Text(
                                        text = stringResource(if (currentScreen == AppScreen.Settings) R.string.screen_settings else R.string.screen_about),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            navigationIcon = {
                                if (currentScreen != AppScreen.Settings) {
                                    IconButton(onClick = { currentScreen = AppScreen.Settings }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back_to_settings))
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen == AppScreen.Settings) {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu_options))
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_export_config)) },
                                            onClick = { showMenu = false; exportLauncher.launch("sbp_config.json") },
                                            leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_import_config)) },
                                            onClick = { showMenu = false; importLauncher.launch("application/json") },
                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_about)) },
                                            onClick = { showMenu = false; currentScreen = AppScreen.About },
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        AnimatedVisibility(
                            visible = currentScreen == AppScreen.Settings && !isImeVisible,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ActionButton(
                                        text = stringResource(R.string.btn_force_stop),
                                        icon = Icons.Default.PowerSettingsNew,
                                        containerColor = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        coroutineScope.launch {
                                            val stopped = AppUtils.forceStopSwiftBackup(context)
                                            snackbarHostState.showSnackbar(
                                                if (stopped) getString(R.string.msg_swift_backup_force_stopped)
                                                else getString(R.string.msg_opened_swift_backup_settings)
                                            )
                                        }
                                    }
                                    ActionButton(
                                        text = stringResource(R.string.btn_open_app),
                                        icon = Icons.AutoMirrored.Filled.Launch,
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        AppUtils.openSwiftBackup(context)
                                    }
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize()
                            .imePadding()
                    ) {
                        AnimatedContent(targetState = currentScreen, label = "ScreenTransition") { screen ->
                            when (screen) {
                                AppScreen.About -> AboutScreen()
                                AppScreen.Settings -> SettingsScreenContent(
                                    prefs = prefs,
                                    onImportGoogleServices = { uri -> viewModel.importGoogleServices(contentResolver, uri, prefs) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        makeWorldReadable()
    }

    override fun onPause() {
        super.onPause()
        makeWorldReadable()
    }

    private fun makeWorldReadable() {
        try {
            val dataDir = java.io.File(applicationInfo.dataDir)
            dataDir.setReadable(true, false)
            dataDir.setExecutable(true, false)
            val user0Dir = java.io.File("/data/user/0/${BuildConfig.APPLICATION_ID}")
            if (user0Dir.exists()) {
                user0Dir.setReadable(true, false)
                user0Dir.setExecutable(true, false)
            }
            val prefsDir = java.io.File(dataDir, "shared_prefs")
            val prefsFile = java.io.File(prefsDir, "${BuildConfig.APPLICATION_ID}_preferences.xml")
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
            }
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }
        } catch (_: Throwable) {}
    }
}

@Composable
private fun SettingsScreenContent(
    prefs: PreferencesManager,
    onImportGoogleServices: (android.net.Uri) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            SettingsSwitch(
                label = stringResource(R.string.pref_enable_premium_title),
                secondaryLabel = stringResource(R.string.pref_enable_premium_subtitle),
                pref = prefs.enablePremium,
                onPrefChange = { prefs.enablePremium = it }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitch(
                label = stringResource(R.string.pref_disable_telemetry_title),
                secondaryLabel = stringResource(R.string.pref_disable_telemetry_subtitle),
                pref = prefs.disableTelemetry,
                onPrefChange = { prefs.disableTelemetry = it }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            SettingsSwitch(
                label = stringResource(R.string.pref_custom_firebase_title),
                secondaryLabel = stringResource(R.string.pref_custom_firebase_subtitle),
                pref = prefs.customFirebaseApp,
                onPrefChange = {
                    prefs.customFirebaseApp = it
                    if (!it) {
                        prefs.enableDriveDiscovery = false
                    }
                }
            )

            AnimatedVisibility(
                visible = prefs.customFirebaseApp,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    GuidedSetupWizard(prefs = prefs, onImportGoogleServices = onImportGoogleServices)
                }
            }
        }

        AdvancedSettingsCard(prefs = prefs)

        // Extra spacing so fields near the bottom can comfortably scroll above the keyboard
        Spacer(modifier = Modifier.height(64.dp))
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = modifier.heightIn(min = 46.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true)
    }
}
