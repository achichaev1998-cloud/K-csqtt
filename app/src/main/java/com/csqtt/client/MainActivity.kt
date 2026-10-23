Package com.csqtt.client

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.csqtt.client.ui.AppUpdateDialog
import com.csqtt.client.ui.ConnectionTab
import com.csqtt.client.ui.ExceptionsTab
import com.csqtt.client.ui.FloatingToolbar
import com.csqtt.client.ui.LogsTab
import com.csqtt.client.ui.SettingsTab
import com.csqtt.client.CsqttConstants
import com.csqtt.client.ui.DeployTab
import com.csqtt.client.ui.InfoTab
import com.csqtt.client.ui.components.LocalCsqttHeaderActions
import com.csqtt.client.ui.design.CsqttMotion
import com.csqtt.client.ui.design.CsqttShapes
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
                            scope.launch {
                                settingsStore.saveThemeMode(mode)
                            }
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

internal fun navigationSwipeProgress(totalDrag: Float, containerWidth: Int): Float =
    if (containerWidth <= 0) 0f else (abs(totalDrag) / (containerWidth.toFloat() * 0.25f)).coerceIn(0f, 1f)

internal fun shouldCommitNavigationSwipe(progress: Float): Boolean = progress > 0.4f

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
    val unreadErrors by TunnelManager.unreadErrorCount.collectAsStateWithLifecycle()
    val tunnelRunning by TunnelManager.running.collectAsStateWithLifecycle()
    val tunnelAutoPausedForWifi by TunnelManager.autoPausedForWifi.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val activeProfile by settingsStore.activeProfile.collectAsStateWithLifecycle(initialValue = 0)
    val deploySettings by settingsStore.deploySettingsSnapshot.collectAsStateWithLifecycle(
        initialValue = DeploySettingsSnapshot()
    )
    val tunnelAuthSettings by settingsStore.tunnelAuthSnapshot.collectAsStateWithLifecycle(
        initialValue = TunnelAuthSnapshot()
    )
    val csqttLinkMode = tunnelAuthSettings.csqttLinkMode
    val participantShowLogs by settingsStore.participantShowLogs.collectAsStateWithLifecycle(initialValue = false)
    val autoPauseOnWifi by settingsStore.autoPauseOnWifi.collectAsStateWithLifecycle(initialValue = false)
    val excludedApps by settingsStore.excludedApps.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedExcludedApps ?: ""
    )
    val whitelistApps by settingsStore.whitelistApps.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedWhitelistApps ?: ""
    )
    val showSystemApps by settingsStore.showSystemApps.collectAsStateWithLifecycle(initialValue = false)
    val isWhitelist by settingsStore.isWhitelist.collectAsStateWithLifecycle(
        initialValue = SettingsStore.cachedIsWhitelist ?: false
    )
    val infoActionsExpanded by settingsStore.infoActionsExpanded.collectAsStateWithLifecycle(initialValue = false)
    val infoProjectExpanded by settingsStore.infoProjectExpanded.collectAsStateWithLifecycle(initialValue = false)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var tunnelValidationRequest by rememberSaveable { mutableIntStateOf(0) }
    val dragTargetIndex = remember { mutableIntStateOf(-1) }
    val dragProgress = remember { mutableFloatStateOf(0f) }
    var wasRunning by remember { mutableStateOf(tunnelRunning) }
    val updateCheckIntervalHours by settingsStore.updateCheckIntervalHours.collectAsStateWithLifecycle(
        initialValue = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS
    )
    var pendingRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val currentVersion = remember { "v${BuildConfig.VERSION_NAME.removePrefix("v")}" }
    val safeBottomInset = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val navToolbarHeight = 66.dp
    val navToolbarBottomMargin = 10.dp
    val navContentGap = 5.dp
    val navOverlayReserve = safeBottomInset + navToolbarBottomMargin + navToolbarHeight + navContentGap
    val navigationDragSlop = with(density) { 6.dp.toPx() }

    val serverRoutingActive by TunnelManager.serverRoutingActive.collectAsStateWithLifecycle()

    val activeNavItems = remember(csqttLinkMode, participantShowLogs) {
        val allowedIds = resolveActiveNavIds(csqttLinkMode, participantShowLogs)
        navItems.filter { it.id in allowedIds }
    }
    val tabStateHolder = rememberSaveableStateHolder()
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

    LaunchedEffect(updateCheckIntervalHours) {
        if (updateCheckIntervalHours == UPDATE_CHECK_NEVER) return@LaunchedEffect

        val intervalMillis = updateIntervalHoursToMillis(updateCheckIntervalHours)
            ?: updateIntervalHoursToMillis(DEFAULT_UPDATE_CHECK_INTERVAL_HOURS)
            ?: 12L * 60L * 60L * 1000L

        suspend fun runUpdateCheck(reason: String) {
            val checkedAt = System.currentTimeMillis()
            val release = fetchLatestReleaseInfo(currentVersion)
            settingsStore.saveUpdateState(
                lastCheckAt = checkedAt,
                latestVersion = release?.versionTag ?: "",
                error = if (release == null) "Не удалось проверить" else ""
            )

            if (release == null) {
                Log.w("CSQTT", "[WARN] Update check: no release info, local=$currentVersion reason=$reason")
                return
            }

            val hasUpdate = isNewerVersion(currentVersion, release.versionTag)
            val postponeVer = settingsStore.updatePostponeVersion.first()
            val postponeUntil = settingsStore.updatePostponeUntil.first()
            val isPostponed = postponeVer == release.versionTag && checkedAt < postponeUntil
            Log.i(
                "CSQTT",
                "Update check: local=$currentVersion remote=${release.versionTag} newer=$hasUpdate postponed=$isPostponed reason=$reason"
            )

            if (hasUpdate && !isPostponed) {
                settingsStore.saveUpdateDialogShown(release.versionTag, checkedAt)
                pendingRelease = release
            }
        }

        runUpdateCheck("startup")

        while (isActive) {
            val now = System.currentTimeMillis()
            val lastCheck = settingsStore.updateLastCheckAt.first()
            val nextCheckAt = lastCheck + intervalMillis
            val waitMs = (nextCheckAt - now).coerceAtLeast(intervalMillis)
            delay(waitMs)
            if (isActive) {
                runUpdateCheck("periodic")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppBackdrop(modifier = Modifier.matchParentSize())

        CompositionLocalProvider(
            LocalCsqttHeaderActions provides {
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
                        .pointerInput(selectedTab, csqttLinkMode, participantShowLogs) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                dragTargetIndex.intValue = -1
                                dragProgress.floatValue = 0f
                            },
