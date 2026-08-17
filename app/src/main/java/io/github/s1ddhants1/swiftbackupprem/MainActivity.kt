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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    private var xposedService by mutableStateOf<XposedService?>(null)
    private var isFrameworkConnected by mutableStateOf(false)
    private var frameworkName by mutableStateOf("")
    private var frameworkVersion by mutableStateOf("")

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
                    isFrameworkConnected = true
                    frameworkName = attempt("get frameworkName", silent = true) { service.frameworkName } ?: "LSPosed"
                    frameworkVersion = attempt("get frameworkVersion", silent = true) { service.frameworkVersion } ?: ""

                    attempt("retrieve remote preferences from XposedService") {
                        val remotePrefs = service.getRemotePreferences("settings")
                        prefsState.value = PreferencesManager(remotePrefs)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w("SBP", "XposedService disconnected")
                    xposedService = null
                    isFrameworkConnected = false
                }
            })
        }

        setContent {
            val context = LocalContext.current
            val prefs = prefsState.value

            var currentScreen by remember { mutableStateOf(AppScreen.Settings) }
            var showMenu by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()

            BackHandler(enabled = currentScreen != AppScreen.Settings) {
                currentScreen = AppScreen.Settings
            }

            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) {
                    viewModel.exportConfig(contentResolver, uri, prefs) { result ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                if (result.isSuccess) "Config exported" else "Export failed: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}"
                            )
                        }
                    }
                }
            }

            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    viewModel.importConfig(contentResolver, uri, prefs) { result ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                if (result.isSuccess) "Config imported" else "Import failed: ${result.exceptionOrNull()?.localizedMessage ?: "unknown error"}"
                            )
                        }
                    }
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
                                        AppScreen.Settings -> "SwiftBackupPrem"
                                        AppScreen.About -> "About"
                                    }
                                )
                            },
                            navigationIcon = {
                                if (currentScreen != AppScreen.Settings) {
                                    IconButton(onClick = { currentScreen = AppScreen.Settings }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
                                    }
                                }
                            },
                            actions = {
                                if (currentScreen == AppScreen.Settings) {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Menu Options")
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Export Config") },
                                            onClick = {
                                                showMenu = false
                                                exportLauncher.launch("sbp_config.json")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Upload, contentDescription = "Export")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Import Config") },
                                            onClick = {
                                                showMenu = false
                                                importLauncher.launch("application/json")
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Download, contentDescription = "Import")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("About") },
                                            onClick = {
                                                showMenu = false
                                                currentScreen = AppScreen.About
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Default.Info, contentDescription = "About")
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
                                                    if (stopped) "Swift Backup force-stopped" else "Opened Swift Backup app settings"
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Force Stop", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true)
                                    }

                                    Button(
                                        onClick = { AppUtils.openSwiftBackup(context) },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open App", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, softWrap = true)
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
                                        val isDark = isSystemInDarkTheme()
                                        val warningContainer = if (isDark) Color(0xFF3E2D12) else Color(0xFFFFF3E0)
                                        val onWarningContainer = if (isDark) Color(0xFFFFDCBE) else Color(0xFF7A3300)
                                        val warningIconColor = if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)

                                        ElevatedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = if (isFrameworkConnected)
                                                    MaterialTheme.colorScheme.primaryContainer
                                                else
                                                    warningContainer
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isFrameworkConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = if (isFrameworkConnected) MaterialTheme.colorScheme.primary else warningIconColor
                                                )
                                                Column {
                                                    Text(
                                                        text = if (isFrameworkConnected) "LSPosed Module Active" else "LSPosed Module Not Enabled",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isFrameworkConnected) MaterialTheme.colorScheme.onPrimaryContainer else onWarningContainer
                                                    )
                                                    Text(
                                                        text = if (isFrameworkConnected)
                                                            "Connected to $frameworkName $frameworkVersion"
                                                        else
                                                            "Enable module in LSPosed manager and reboot.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (isFrameworkConnected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else onWarningContainer.copy(alpha = 0.85f)
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
                                                label = "Enable Premium",
                                                secondaryLabel = "Unlock Swift Backup premium features and bypass license checks",
                                                pref = prefs.enablePremium,
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
                                                label = "Disable Telemetry & Tracking",
                                                secondaryLabel = "Block Firebase Analytics, Crashlytics, Sessions, Installations, and DataTransport",
                                                pref = prefs.disableTelemetry,
                                                onPrefChange = { prefs.disableTelemetry = it }
                                            )
                                        }

                                        ElevatedCard(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.elevatedCardColors(
                                                containerColor = if (prefs.customFirebaseApp)
                                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                else
                                                    MaterialTheme.colorScheme.surface
                                            )
                                        ) {
                                            Column {
                                                SettingsSwitch(
                                                    label = "Custom firebase app",
                                                    secondaryLabel = "Recommended, forces Swift Backup to use your own firebase credentials",
                                                    pref = prefs.customFirebaseApp,
                                                    onPrefChange = { prefs.customFirebaseApp = it }
                                                )

                                                AnimatedVisibility(
                                                    visible = prefs.customFirebaseApp,
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
                                                            prefs = prefs
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
