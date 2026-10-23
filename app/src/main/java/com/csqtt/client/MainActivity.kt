package com.csqtt.client

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import com.csqtt.client.ui.ConnectionTab
import com.csqtt.client.ui.ExceptionsTab
import com.csqtt.client.ui.FloatingToolbar
import com.csqtt.client.ui.LogsTab
import com.csqtt.client.ui.SettingsTab
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.DeployTab
import com.csqtt.client.ui.components.LocalCsqttHeaderActions
import com.csqtt.client.ui.dialogs.VkAuthDialog
import com.csqtt.client.ui.utils.parseCsqttLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val appSettingsStore by lazy { SettingsStore(applicationContext) }
    private var renderUi by mutableStateOf(false)
    private var showVkAuthDialog by mutableStateOf(false)
    private var batteryPromptInFlight = false

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryPromptInFlight = false
        lifecycleScope.launch {
            appSettingsStore.markBatteryOptimizationPromptHandled()
        }
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        checkAndRequestBattery()
    }

    companion object {
        @Volatile
        var activeActivities = 0
        val isForeground: Boolean
            get() = activeActivities > 0
    }

    override fun onStart() {
        super.onStart()
        activeActivities++
        renderUi = true
        ManlCaptchaWebViewManager.checkAndShowPendingCaptcha(this)
    }

    override fun onStop() {
        renderUi = false
        activeActivities = (activeActivities - 1).coerceAtLeast(0)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val discardStaleUiState = UpdateLaunchGuard.consumeStaleSavedState(this)
        super.onCreate(if (discardStaleUiState) null else savedInstanceState)

        importCsqttLink(intent)
        TunnelManager.initObservers(this)
        enableEdgeToEdge()
        checkAndRequestNotifications()

        setContent {
            val authContext = LocalContext.current.applicationContext
            val authScope = rememberCoroutineScope()
            val uiStateHolder = rememberSaveableStateHolder()
            if (renderUi) {
                uiStateHolder.SaveableStateProvider("main_ui") {
                    val settingsStore = remember { appSettingsStore }
                    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = CsqttConstants.Theme.MODE_SYSTEM)
                    val activeFingerprint by settingsStore.selectedFingerprint.collectAsStateWithLifecycle(initialValue = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT)
                    val scope = rememberCoroutineScope()

                    val baseDensity = LocalDensity.current
                    val fixedDensity = remember(baseDensity.density) { Density(baseDensity.density, 1f) }

                    CompositionLocalProvider(LocalDensity provides fixedDensity) {
                        CSQTTTheme(themeMode = themeMode) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background,
                            ) {
                                MainScreen(
                                    settingsStore = settingsStore,
                                    themeMode = themeMode,
                                    onThemeChange = { mode ->
                                        scope.launch { settingsStore.saveThemeMode(mode) }
                                    },
                                    activeFingerprint = activeFingerprint,
                                    onFingerprintChange = { fp ->
                                        scope.launch { settingsStore.saveFingerprint(fp) }
                                    },
                                    onVkAuthRequested = { showVkAuthDialog = true },
                                )
                            }
                        }
                    }
                }
            }
            if (showVkAuthDialog) {
                VkAuthDialog(
                    onToken = { payload ->
                        showVkAuthDialog = false
                        authScope.launch {
                            appSettingsStore.saveVkAccessToken(payload.token, payload.userId)
                            authContext.showRaisedToast("Вечный VK access token сохранен", Toast.LENGTH_SHORT)
                        }
                    },
                    onDismiss = { showVkAuthDialog = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importCsqttLink(intent)
    }

    private fun importCsqttLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        val link = parseCsqttLink(raw)
        if (link == null) {
            showRaisedToast("Неверная ссылка CSQTT", Toast.LENGTH_SHORT)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Импорт конфигурации CSQTT")
            .setMessage("Хост: ${link.host}\nPeer: ${link.port}\n\nЗаменить текущую конфигурацию?")
            .setPositiveButton("Импортировать") { _, _ ->
                lifecycleScope.launch {
                    appSettingsStore.saveCsqttParticipantLink(raw)
                    showRaisedToast("Конфигурация CSQTT успешно подключена", Toast.LENGTH_SHORT)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun checkAndRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                checkAndRequestBattery()
            }
        } else {
            checkAndRequestBattery()
        }
    }

    @SuppressLint("BatteryLife")
    private fun checkAndRequestBattery() {
        if (batteryPromptInFlight) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        lifecycleScope.launch {
            val shouldRequest = shouldRequestBatteryOptimizationExemption(
                promptHandled = appSettingsStore.isBatteryOptimizationPromptHandled(),
                alreadyExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
            )
            if (!shouldRequest) {
                if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    appSettingsStore.markBatteryOptimizationPromptHandled()
                }
                return@launch
            }

            batteryPromptInFlight = true
            val launched = runCatching {
                batteryLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        "package:$packageName".toUri(),
                    ),
                )
                true
            }.getOrDefault(false)
            if (!launched) {
                batteryPromptInFlight = false
                appSettingsStore.markBatteryOptimizationPromptHandled()
            }
        }
    }
}

private data class NavItem(
    val id: Int,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem(0, R.string.nav_connection, Icons.Filled.PowerSettingsNew, Icons.Filled.PowerSettingsNew),
    NavItem(1, R.string.nav_tunnel, Icons.Filled.VpnKey, Icons.Outlined.VpnKey),
    NavItem(2, R.string.nav_deploy, Icons.Filled.Cloud, Icons.Outlined.Cloud),
    NavItem(3, R.string.nav_logs, Icons.Filled.Terminal, Icons.Outlined.Terminal),
    NavItem(4, R.string.nav_exceptions, Icons.Filled.FilterList, Icons.Outlined.FilterList),
)

internal fun resolveActiveNavIds(csqttLinkMode: Boolean, participantShowLogs: Boolean): List<Int> {
    val allIds = listOf(0, 1, 2, 3, 4)
    return allIds.filter { id ->
        if (csqttLinkMode) {
            if (id == 2) return@filter false
            if (id == 3 && !participantShowLogs) return@filter false
        }
        true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settingsStore: SettingsStore,
    themeMode: String = CsqttConstants.Theme.MODE_SYSTEM,
    onThemeChange: (String) -> Unit = {},
    activeFingerprint: String = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT,
    onFingerprintChange: (String) -> Unit = {},
    onVkAuthRequested: () -> Unit = {},
) {
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelAutoPausedForWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeProfile by settingsStore.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    val tunnelAuthSettings by settingsStore.tunnelAuthSnapshot.collectAsStateWithLifecycle(
        initialValue = TunnelAuthSnapshot()
    )
    val csqttLinkMode = tunnelAuthSettings.csqttLinkMode
    val participantShowLogs by settingsStore.participantShowLogs.collectAsStateWithLifecycle(initialValue = false)
    val autoPauseOnWifi by settingsStore.autoPauseOnWifi.collectAsStateWithLifecycle(initialValue = false)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(tunnelRunning) }
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }

    val activeNavItems = remember(csqttLinkMode, participantShowLogs) {
        val allowedIds = resolveActiveNavIds(csqttLinkMode, participantShowLogs)
        navItems.filter { it.id in allowedIds }
    }
    
    LaunchedEffect(csqttLinkMode, participantShowLogs) {
        if (csqttLinkMode) {
            if (selectedTab == 2 || (selectedTab == 3 && !participantShowLogs)) {
                selectedTab = 0
            }
        }
    }
    LaunchedEffect(activeNavItems) {
        if (activeNavItems.none { it.id == selectedTab }) {
            selectedTab = 0
        }
    }

    LaunchedEffect(tunnelRunning, tunnelAutoPausedForWifi) {
        if (wasRunning && !tunnelRunning && !tunnelAutoPausedForWifi) {
            TunnelManager.startCooldown(CsqttConstants.Timeouts.VPN_PERMISSION_COOLDOWN_MS)
        }
        wasRunning = tunnelRunning
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 3) TunnelManager.clearUnreadErrors()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        CompositionLocalProvider(
            LocalCsqttHeaderActions provides {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingToolbar(
                        settingsStore = settingsStore,
                        csqttLinkMode = csqttLinkMode,
                        activeProfile = activeProfile,
                        onActiveProfileChange = { profile ->
                            scope.launch { settingsStore.saveActiveProfile(profile) }
                        },
                        currentTheme = themeMode,
                        onThemeChange = onThemeChange,
                        activeFingerprint = activeFingerprint,
                        onFingerprintChange = onFingerprintChange,
                        autoPauseOnWifi = autoPauseOnWifi,
                        onAutoPauseOnWifiChange = { enabled ->
                            scope.launch { settingsStore.saveAutoPauseOnWifi(enabled) }
                        },
                    )

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Меню",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Авторизация VK") },
                                onClick = {
                                    menuExpanded = false
                                    onVkAuthRequested()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("О приложении") },
                                onClick = {
                                    menuExpanded = false
                                    Toast.makeText(context, "CSQTT Client $currentVersion", Toast.LENGTH_SHORT).show()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                containerColor = Color.Transparent,
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                ) {
                    when (selectedTab) {
                        0 -> ConnectionTab(settingsStore = settingsStore)
                        1 -> SettingsTab(settingsStore = settingsStore)
                        2 -> DeployTab(settingsStore = settingsStore)
                        3 -> LogsTab()
                        4 -> ExceptionsTab(settingsStore = settingsStore)
                    }
                }
            }
        }
    }
}
