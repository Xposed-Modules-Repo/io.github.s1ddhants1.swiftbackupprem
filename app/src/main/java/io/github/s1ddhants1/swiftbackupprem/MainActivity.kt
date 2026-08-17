package io.github.s1ddhants1.swiftbackupprem

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.s1ddhants1.swiftbackupprem.ui.MainViewModel
import io.github.s1ddhants1.swiftbackupprem.ui.component.AboutScreen
import io.github.s1ddhants1.swiftbackupprem.ui.component.GuidedSetupWizard
import io.github.s1ddhants1.swiftbackupprem.ui.component.SettingsSwitch
import io.github.s1ddhants1.swiftbackupprem.ui.theme.Theme
import io.github.s1ddhants1.swiftbackupprem.util.AppUtils
import io.github.s1ddhants1.swiftbackupprem.util.PreferencesManager
import io.github.s1ddhants1.swiftbackupprem.util.attempt
import kotlinx.coroutines.launch

enum class AppScreen {
    Settings, About
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private var xposedService: XposedService? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Initialize local SharedPreferences as fallback
        val localPrefs: SharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val prefsState = mutableStateOf(PreferencesManager(localPrefs))

        // Register listener for Modern Xposed API Framework Service
        attempt("register XposedServiceHelper listener") {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i("SBP", "XposedService bound: ${service.frameworkName} ${service.frameworkVersion} (API ${service.apiVersion})")
                    xposedService = service
                    val name = attempt("get frameworkName", silent = true) { service.frameworkName } ?: "LSPosed"
                    val version = attempt("get frameworkVersion", silent = true) { service.frameworkVersion } ?: ""
                    viewModel.onFrameworkConnected(name, version)

                    attempt("retrieve remote preferences from XposedService") {
                        val remotePrefs = service.getRemotePreferences("settings")
                        prefsState.value = PreferencesManager(remotePrefs)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w("SBP", "XposedService disconnected")
                    xposedService = null
                    viewModel.onFrameworkDisconnected()
                }
            })
        }

        setContent {
            val context = LocalContext.current
            val prefs = prefsState.value
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            var currentScreen by remember { mutableStateOf(AppScreen.Settings) }
            var showMenu by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()

            BackHandler(enabled = currentScreen != AppScreen.Settings) {
                currentScreen = AppScreen.Settings
            }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is io.github.s1ddhants1.swiftbackupprem.ui.MainUiEvent.ConfigExported -> {
                            snackbarHostState.showSnackbar(
                                if (event.success) this@MainActivity.getString(R.string.msg_config_exported)
                                else this@MainActivity.getString(R.string.msg_export_failed, event.error ?: "unknown error")
                            )
                        }
                        is io.github.s1ddhants1.swiftbackupprem.ui.MainUiEvent.ConfigImported -> {
                            snackbarHostState.showSnackbar(
                                if (event.success) this@MainActivity.getString(R.string.msg_config_imported)
                                else this@MainActivity.getString(R.string.msg_import_failed, event.error ?: "unknown error")
                            )
                        }
                    }
                }
            }

            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) {
                    viewModel.exportConfig(contentResolver, uri, prefs)
                }
            }

            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    viewModel.importConfig(contentResolver, uri, prefs)
                }
            }

            Theme {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = when (currentScreen) {
                                        AppScreen.Settings -> stringResource(R.string.screen_settings)
                                        AppScreen.About -> stringResource(R.string.screen_about)
                                    }
                                )
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
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_export_config)) },
                                            onClick = {
                                                showMenu = false
                                                exportLauncher.launch("sbp_config.json")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.menu_export_config))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_import_config)) },
                                            onClick = {
                                                showMenu = false
                                                importLauncher.launch("application/json")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.menu_import_config))
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.menu_about)) },
                                            onClick = {
                                                showMenu = false
                                                currentScreen = AppScreen.About
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.menu_about))
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    },
                    bottomBar = {
                        AnimatedVisibility(
                            visible = currentScreen == AppScreen.Settings,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            Surface(
                                tonalElevation = 3.dp,
                                shadowElevation = 4.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            coroutineScope.launch {
                                                val stopped = AppUtils.forceStopSwiftBackup(context)
                                                snackbarHostState.showSnackbar(
                                                    if (stopped) this@MainActivity.getString(R.string.msg_swift_backup_force_stopped)
                                                    else this@MainActivity.getString(R.string.msg_opened_swift_backup_settings)
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.btn_force_stop), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true)
                                    }

                                    Button(
                                        onClick = { AppUtils.openSwiftBackup(context) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.btn_open_app), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true)
                                    }
                                }
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                        AnimatedContent(
                            targetState = currentScreen,
                            label = "ScreenTransition"
                        ) { screen ->
                            when (screen) {
                                AppScreen.About -> AboutScreen()
                                AppScreen.Settings -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState())
                                            .padding(vertical = 8.dp)
                                    ) {
                                        // Framework status card
                                        val statusContainerColor = if (state.isFrameworkConnected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        }
                                        val onStatusContainerColor = if (state.isFrameworkConnected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        }
                                        val statusIconTint = if (state.isFrameworkConnected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }

                                        ElevatedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = statusContainerColor
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (state.isFrameworkConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = statusIconTint
                                                )
                                                Column {
                                                    Text(
                                                        text = if (state.isFrameworkConnected) stringResource(R.string.framework_active_title) else stringResource(R.string.framework_inactive_title),
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = onStatusContainerColor
                                                    )
                                                    Text(
                                                        text = if (state.isFrameworkConnected)
                                                            stringResource(R.string.framework_active_desc, state.frameworkName, state.frameworkVersion)
                                                        else
                                                            stringResource(R.string.framework_inactive_desc),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = onStatusContainerColor.copy(alpha = 0.85f)
                                                    )
                                                }
                                            }
                                        }

                                        ElevatedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                            )
                                        ) {
                                            SettingsSwitch(
                                                label = stringResource(R.string.pref_enable_premium_title),
                                                secondaryLabel = stringResource(R.string.pref_enable_premium_subtitle),
                                                pref = prefs.enablePremium,
                                                enabled = state.isFrameworkConnected,
                                                onPrefChange = { prefs.enablePremium = it }
                                            )
                                        }

                                        ElevatedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                            )
                                        ) {
                                            SettingsSwitch(
                                                label = stringResource(R.string.pref_disable_telemetry_title),
                                                secondaryLabel = stringResource(R.string.pref_disable_telemetry_subtitle),
                                                pref = prefs.disableTelemetry,
                                                enabled = state.isFrameworkConnected,
                                                onPrefChange = { prefs.disableTelemetry = it }
                                            )
                                        }

                                        ElevatedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = if (state.isFrameworkConnected && prefs.customFirebaseApp)
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                else
                                                    MaterialTheme.colorScheme.surface
                                            )
                                        ) {
                                            Column {
                                                SettingsSwitch(
                                                    label = stringResource(R.string.pref_custom_firebase_title),
                                                    secondaryLabel = stringResource(R.string.pref_custom_firebase_subtitle),
                                                    pref = prefs.customFirebaseApp,
                                                    enabled = state.isFrameworkConnected,
                                                    onPrefChange = { prefs.customFirebaseApp = it }
                                                )

                                                AnimatedVisibility(
                                                    visible = state.isFrameworkConnected && prefs.customFirebaseApp,
                                                    enter = expandVertically() + fadeIn(),
                                                    exit = shrinkVertically() + fadeOut()
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 8.dp)
                                                    ) {
                                                        HorizontalDivider(
                                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                                        )
                                                        GuidedSetupWizard(
                                                            prefs = prefs,
                                                            onImportGoogleServices = { uri ->
                                                                viewModel.importGoogleServices(contentResolver, uri, prefs)
                                                            }
                                                        )
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
            }
        }
    }
}
