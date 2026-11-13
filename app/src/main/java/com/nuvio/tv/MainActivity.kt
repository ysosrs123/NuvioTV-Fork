package com.nuvio.tv

import androidx.compose.runtime.SideEffect
import com.nuvio.tv.data.local.DeviceUiPreferenceStore
import com.nuvio.tv.data.local.V2AppearancePreferenceStore
import com.nuvio.tv.ui.v2.appearance.ResolvedAppearance
import com.nuvio.tv.ui.v2.appearance.rememberStableUiScale
import com.nuvio.tv.ui.v2.appearance.rememberUiCanvasSnapshot
import com.nuvio.tv.ui.v2.scale.isPlayerUiVisible
import com.nuvio.tv.ui.v2.profile.startPicture
import com.nuvio.tv.ui.v2.profile.startPictureOr
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import com.nuvio.tv.ui.screens.settings.FormatCheckHost
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.ui.screens.ambient.AMBIENT_BEFORE_OLED_DIM
import com.nuvio.tv.ui.screens.ambient.AmbientIdleController
import com.nuvio.tv.ui.screens.ambient.AmbientModeHost
import com.nuvio.tv.ui.screens.ambient.LocalAmbientIdleController
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.contentColorFor
import androidx.tv.material3.rememberDrawerState
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.core.deeplink.DeepLinkHandler
import com.nuvio.tv.core.deeplink.DeepLinkParser
import com.nuvio.tv.core.player.PlayerWindowBackdrop
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.ProfileSyncService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.tracking.TrackingProgressRefreshCoordinator
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.AppOnboardingDataStore
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.resolveCustomThemeColors
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.CardDepthStyle
import com.nuvio.tv.domain.model.CosmeticEntitlement
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.resolveAppTheme
import com.nuvio.tv.domain.deeplink.AppDeepLink
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.components.NuvioScrollDefaults
import com.nuvio.tv.ui.components.BrandWordmark
import com.nuvio.tv.ui.components.ScreensaverOverlay
import com.nuvio.tv.ui.components.LocalCardDepthStyle
import com.nuvio.tv.ui.components.LocalLandscapePosterMode
import com.nuvio.tv.ui.components.LocalAlwaysBackdropWithLogo
import com.nuvio.tv.ui.components.LocalLogoOverCardTrailer
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.navigation.LocalNavViewModelStoreOwner
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.navigation.rememberNavSessionViewModelStoreOwner
import com.nuvio.tv.ui.screens.calendar.openCalendarWatchTarget
import com.nuvio.tv.ui.membership.LocalMemberAccess
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.addon.EssentialAddonSetupScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.theme.NuvioComponents
import com.nuvio.tv.ui.theme.NuvioLayout
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.ThemeColors
import com.nuvio.tv.ui.theme.accentBrush
import com.nuvio.tv.ui.util.LocalFastHorizontalNavigationEnabled
import com.nuvio.tv.ui.util.LocalRecompositionHighlighterEnabled
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import com.nuvio.tv.ui.util.rememberRawSvgPainter
import com.nuvio.tv.updater.UpdateViewModel
import com.nuvio.tv.updater.ui.UpdateBannerHost
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeEffect
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import dev.chrisbanes.haze.hazeSource
import com.nuvio.tv.core.util.processLocale
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }

private const val SIDEBAR_AUTO_COLLAPSE_DELAY_MS = 3_000L
private const val FIRST_TRACKER_REFRESH_DELAY_MS = 8_000L

private const val MAX_SUPPORTED_FONT_SCALE = 1.15f

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.GLASS,
    val customThemeColors: CustomThemeColors = CustomThemeColors.solid(CustomThemeColors.Default.second),
    val memberAccess: MemberAccess = MemberAccess.None,
    val font: AppFont = AppFont.INTER,
    val amoledMode: Boolean = false,
    val amoledSurfacesMode: Boolean = false,
    val hasChosenLayout: Boolean? = null,
    val experienceMode: ExperienceMode? = null,
    val experienceModeLoaded: Boolean = false,
    val addonSetupSkipped: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurPref: Boolean = false,
    val discoverLocation: DiscoverLocation? = null,
    val smoothBringIntoViewEnabled: Boolean = true,
    val fastHorizontalNavigationEnabled: Boolean = false,
    val composeHighlighterEnabled: Boolean = false,
    val settingsUiStyle: SettingsUiStyle = SettingsUiStyle.CLASSIC,
    val cardDepthStyle: CardDepthStyle = CardDepthStyle(),
    val landscapePosterMode: Boolean = false,
    val alwaysBackdropWithLogo: Boolean = false,
    val logoOverCardTrailer: Boolean = true
)

@AndroidEntryPoint
open class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var experienceModeDataStore: ExperienceModeDataStore

    @Inject
    lateinit var memberAccessRepository: MemberAccessRepository

    @Inject
    lateinit var addonRepository: AddonRepository

    @Inject
    lateinit var trackingProgressRefreshCoordinator: TrackingProgressRefreshCoordinator

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var androidTvChannelSyncService: com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService

    @Inject
    lateinit var profileSyncService: ProfileSyncService

    @Inject
    lateinit var profileManager: ProfileManager

    @Inject
    lateinit var profileLockStateDataStore: com.nuvio.tv.data.local.ProfileLockStateDataStore

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var backupSignInOffer: com.nuvio.tv.core.backup.BackupSignInOffer

    @Inject
    lateinit var backupPartialNotice: com.nuvio.tv.core.backup.BackupPartialNotice

    @Inject
    lateinit var deviceSessionRegistration: DeviceSessionRegistration

    @Inject
    lateinit var authSessionNoticeDataStore: AuthSessionNoticeDataStore

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var avatarRepository: AvatarRepository

    @Inject
    lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    @Inject
    lateinit var externalPlaybackTracker: com.nuvio.tv.core.player.ExternalPlaybackTracker

    @Inject
    lateinit var partyRuntime: com.nuvio.tv.core.party.PartyRuntime

    @Inject
    lateinit var localProfileAvatars: com.nuvio.tv.core.profile.LocalProfileAvatars

    @Inject
    lateinit var deepLinkHandler: DeepLinkHandler

    @Inject
    lateinit var screensaverController: com.nuvio.tv.core.player.ScreensaverController

    @Inject
    lateinit var ambientModeDataStore: com.nuvio.tv.data.local.AmbientModeDataStore

    @Inject
    lateinit var calendarSettingsDataStore: com.nuvio.tv.core.calendar.CalendarSettingsDataStore

    @Inject
    lateinit var calendarReminders: com.nuvio.tv.core.calendar.CalendarReminderCenter

    @Inject
    lateinit var calendarAppVisibility: com.nuvio.tv.core.calendar.CalendarAppVisibility

    @Inject
    lateinit var calendarEpisodeResolver: dagger.Lazy<com.nuvio.tv.core.calendar.CalendarEpisodeResolver>

    private val pendingDeepLinkUrl = MutableStateFlow<String?>(null)
    private val pendingLaunchIntent = MutableStateFlow<Intent?>(null)

    private lateinit var jankStats: JankStats
    private val qualityGovernor = com.nuvio.tv.ui.v2.quality.AutomaticQualityGovernor()

    /** Activity-level launcher for external video players. Survives all navigation changes. */
    private val externalPlayerLauncher = registerForActivityResult(
        com.nuvio.tv.core.player.ExternalPlayerResultContract()
    ) { result ->
        Log.d("MainActivity", "External player ActivityResult: $result")
        externalPlaybackTracker.onActivityResult(result)
    }

    /** True until the first onResume after onCreate completes. */
    private var isFirstResumeAfterCreate = false

    /** True after an idle-overlay wake until the waking press's ACTION_UP has been swallowed. */
    private var swallowKeysUntilUp = false
    private val ambientIdleController by lazy { AmbientIdleController(screensaverController) }
    private var calendarMenuKeyDown = false
    @Volatile private var searchKeyOpensSearch = false
    private val searchKeyPresses = mutableIntStateOf(0)

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET }
        val systemLocale = newBase.resources.configuration.locales.get(0) ?: Locale.getDefault()
        Locale.setDefault(processLocale(tag, systemLocale))

        if (!tag.isNullOrEmpty()) {
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            // Cache not ready yet (very early cold start) — use system locale
            // The IO coroutine in Application.onCreate will finish before any activity
            // is usually created, but if not, we just use system locale until next launch
            super.attachBaseContext(newBase)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        com.nuvio.tv.core.util.StartupLatencyTrace.coldOnce("activity_create")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        hideSystemBarsOnHandheld()
        window?.decorView?.let { com.nuvio.tv.core.util.StartupLatencyTrace.coldOnFirstDraw(it, "first_frame") }
        com.nuvio.tv.core.performance.DetailEntryTrace.attach(window)
        isFirstResumeAfterCreate = true
        val startPicture = com.nuvio.tv.ui.v2.profile.StartPicture(
            window?.decorView?.background?.constantState?.newDrawable(resources)?.takeIf {
                savedInstanceState == null && com.nuvio.tv.ui.brand.ArcStartHandoff.windowCarriesMark(android.os.Build.VERSION.SDK_INT)
            }
        ) { window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT)) }
        if (!startPicture.showing) window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        // Wire the Activity-level launcher to the tracker
        externalPlaybackTracker.activityLauncher = externalPlayerLauncher

        PluginRuntimeHooks.onActivityCreate(this)

        // OLED screensaver: 1 Hz idle ticker while STARTED. collectLatest restarts the
        // loop (and the idle clock) whenever the enable/timeout settings change, and the
        // clock also restarts every time the activity returns to STARTED. The dimmer
        // yields to Ambient Mode when both are on (AMBIENT_BEFORE_OLED_DIM).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    themeDataStore.screensaverEnabled,
                    themeDataStore.screensaverTimeoutMinutes,
                    ambientModeDataStore.settings.map { it.enabled }
                ) { enabled, minutes, ambientEnabled ->
                    (enabled && !(ambientEnabled && AMBIENT_BEFORE_OLED_DIM)) to minutes
                }
                    .collectLatest { (enabled, minutes) ->
                        if (!enabled) {
                            screensaverController.notifyWake()
                            return@collectLatest
                        }
                        screensaverController.notifyInteraction()
                        while (true) {
                            delay(1_000)
                            screensaverController.maybeEngage(minutes * 60_000L)
                        }
                    }
            }
        }

        window?.decorView?.post {
            val snapshot = com.nuvio.tv.core.player.DisplayCapabilities.detect(this)
            com.nuvio.tv.core.player.DisplayCapabilities.logSummary(snapshot)
        }

        // Extract extras set by the Continue Watching launcher channel preview programs.
        val launchContentId = intent?.getStringExtra("contentId")
        val launchContentType = intent?.getStringExtra("contentType")
        val launchMode = intent?.getStringExtra("launchMode")
        val launchVideoId = intent?.getStringExtra("videoId")
        val launchName = intent?.getStringExtra("name")
        val launchPoster = intent?.getStringExtra("poster")
        val launchBackdrop = intent?.getStringExtra("backdrop")
        val launchLogo = intent?.getStringExtra("logo")
        val launchSeason = intent?.getIntExtra("season", -1)?.takeIf { it >= 0 }
        val launchEpisode = intent?.getIntExtra("episode", -1)?.takeIf { it >= 0 }
        val launchEpisodeTitle = intent?.getStringExtra("episodeTitle")
        captureDeepLinkIntent(intent)

        setContent { LauncherOnlySemantics(hideSemanticsTree.value) {
            var hasSelectedProfileThisSession by rememberSaveable { mutableStateOf(false) }
            var v2ProfilePickerRequested by rememberSaveable { mutableStateOf(false) }
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            var onboardingProfileSyncInProgress by remember { mutableStateOf(false) }
            val hasSeenAuthQrFlow = remember(appOnboardingDataStore) {
                appOnboardingDataStore.hasSeenAuthQrOnFirstLaunch.map<Boolean, Boolean?> { it }
            }
            val hasSeenAuthQrOnFirstLaunch by hasSeenAuthQrFlow.collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()
            var backupSignInPrompt by remember { mutableStateOf(backupSignInOffer.pending) }
            val backupPartlyRestored by backupPartialNotice.visible.collectAsState()
            val context = LocalContext.current

            LaunchedEffect(authSessionNoticeDataStore, context) {
                authSessionNoticeDataStore.pendingNotice.collect { notice ->
                    if (notice == StartupAuthNotice.NUVIO) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.auth_notice_nuvio_logged_out),
                            Toast.LENGTH_LONG
                        ).show()
                        authSessionNoticeDataStore.consumeNotice(notice)
                    }
                }
            }

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val activeProfileId by profileManager.activeProfileId.collectAsState()
            val profiles by profileManager.profiles.collectAsState()
            val hasEverSelectedProfile by profileManager.hasEverSelectedProfile.collectAsState()
            val rememberLastProfileEnabled by profileManager.rememberLastProfileEnabled.collectAsState()
            val activeProfile = remember(activeProfileId, profiles) {
                profiles.firstOrNull { it.id == activeProfileId }
            }
            val profilePinStates by remember {
                profileLockStateDataStore.pinEnabled.map<Map<Int, Boolean>, Map<Int, Boolean>?> { it }
            }.collectAsState(initial = null)

            LaunchedEffect(authState, profiles) {
                if (authState is AuthState.FullAccount) {
                    profileSyncService.pullProfileLockStates()
                        .onSuccess { profileLockStateDataStore.replaceAll(it) }
                }
            }

            val signedOut = authState is AuthState.SignedOut
            val activeProfileHasPin = remember(activeProfileId, profilePinStates, signedOut) {
                com.nuvio.tv.core.profile.ProfileStartGate.hasPin(signedOut, profilePinStates, activeProfileId)
            }

            LaunchedEffect(hasEverSelectedProfile, activeProfileHasPin, rememberLastProfileEnabled) {
                if (!hasSelectedProfileThisSession && !v2ProfilePickerRequested && com.nuvio.tv.core.profile.ProfileStartGate.canAutoSelect(
                        rememberLastProfileEnabled, hasEverSelectedProfile, activeProfileHasPin,
                        com.nuvio.tv.core.profile.ProfileStartGate.hasPin(signedOut, profilePinStates, profileManager.savedActiveProfileId())
                    )
                ) {
                    hasSelectedProfileThisSession = true
                    if (authManager.authState.value is AuthState.FullAccount) {
                        startupSyncService.requestSyncNow()
                    }
                }
            }

            var avatarCatalog by remember { mutableStateOf(emptyList<com.nuvio.tv.data.remote.supabase.AvatarCatalogItem>()) }
            val avatarMemberAccess by memberAccessRepository.access.collectAsState()
            val hasProfileAvatarAccess = avatarMemberAccess.entitlements
                .includes(CosmeticEntitlement.PROFILE_AVATARS)

            LaunchedEffect(hasProfileAvatarAccess) {
                avatarCatalog = runCatching {
                    avatarRepository.getAvatarCatalog(hasProfileAvatarAccess)
                }
                    .getOrDefault(emptyList())
            }

            val localAvatars by localProfileAvatars.avatars.collectAsState()
            val activeProfileAvatarImageUrl = remember(activeProfile, avatarCatalog, localAvatars) {
                activeProfile?.let { localAvatars[it.id] }?.let(com.nuvio.tv.core.party.PartyAvatars::imageUri)
                    ?: activeProfile?.avatarUrl?.takeIf { it.isNotBlank() }
                    ?: activeProfile?.avatarId?.let { avatarRepository.getAvatarImageUrl(it, avatarCatalog) }
            }

            val mainUiPrefsFlow = remember(
                activeProfileId,
                themeDataStore,
                layoutPreferenceDataStore,
                experienceModeDataStore,
                memberAccessRepository
            ) {
                val activeThemeFlow = combine(
                    themeDataStore.themeSelection,
                    memberAccessRepository.access
                ) { selection, memberAccess ->
                    selection.copy(
                        theme = resolveAppTheme(selection.theme, memberAccess.entitlements),
                        customColors = resolveCustomThemeColors(selection.customColors, memberAccess.tier)
                    ) to memberAccess
                }
                // Group flows into two batches to reduce intermediate flow allocations.
                // Each batch uses a single combine() instead of chaining .combine() calls,
                // which avoids N intermediate flow objects and redundant emissions on startup.
                val themeAndExperienceFlow = combine(
                    activeThemeFlow,
                    themeDataStore.selectedFont,
                    themeDataStore.amoledMode,
                    themeDataStore.amoledSurfacesMode,
                    experienceModeDataStore.mode,
                ) { themeAndAccess, font, amoledMode, amoledSurfacesMode, experienceMode ->
                    MainUiPrefs(
                        theme = themeAndAccess.first.theme ?: AppTheme.GLASS,
                        customThemeColors = themeAndAccess.first.customColors,
                        memberAccess = themeAndAccess.second,
                        font = font,
                        amoledMode = amoledMode,
                        amoledSurfacesMode = amoledSurfacesMode,
                        experienceMode = experienceMode,
                        experienceModeLoaded = true,
                    )
                }
                val layoutAndFeaturesFlow = combine(
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.sidebarCollapsedByDefault,
                    layoutPreferenceDataStore.modernSidebarEnabled,
                    layoutPreferenceDataStore.modernSidebarBlurEnabled,
                    layoutPreferenceDataStore.discoverLocation,
                ) { hasChosenLayout, sidebarCollapsed, modernSidebarEnabled, modernSidebarBlurPref, discoverLocation ->
                    MainUiPrefs(
                        hasChosenLayout = hasChosenLayout,
                        sidebarCollapsed = sidebarCollapsed,
                        modernSidebarEnabled = modernSidebarEnabled,
                        modernSidebarBlurPref = modernSidebarBlurPref,
                        discoverLocation = discoverLocation,
                    )
                }
                val extraFeaturesFlow = combine(
                    experienceModeDataStore.addonSetupSkipped,
                    layoutPreferenceDataStore.smoothBringIntoViewEnabled,
                    layoutPreferenceDataStore.fastHorizontalNavigationEnabled,
                    layoutPreferenceDataStore.composeHighlighterEnabled,
                    themeDataStore.settingsUiStyle,
                ) { addonSetupSkipped, smoothBringIntoView, fastHorizontalNav, composeHighlighter, settingsUiStyle ->
                    MainUiPrefs(
                        addonSetupSkipped = addonSetupSkipped,
                        smoothBringIntoViewEnabled = smoothBringIntoView,
                        fastHorizontalNavigationEnabled = fastHorizontalNav,
                        composeHighlighterEnabled = composeHighlighter,
                        settingsUiStyle = settingsUiStyle,
                    )
                }
                val landscapePrefsFlow = combine(
                    layoutPreferenceDataStore.landscapePosterScope,
                    layoutPreferenceDataStore.alwaysShowLandscapeClearlogo,
                    layoutPreferenceDataStore.focusedPosterBackdropTrailerLogoEnabled
                ) { scope, backdropWithLogo, logoOverTrailer ->
                    Triple(scope.onAllScreens, backdropWithLogo, logoOverTrailer)
                }

                combine(
                    themeAndExperienceFlow,
                    layoutAndFeaturesFlow,
                    extraFeaturesFlow,
                    layoutPreferenceDataStore.cardDepthStyle,
                    landscapePrefsFlow
                ) { themePrefs, layoutPrefs, extraPrefs, cardDepthStyle, (landscapePosterMode, alwaysBackdropWithLogo, logoOverCardTrailer) ->
                    themePrefs.copy(
                        hasChosenLayout = layoutPrefs.hasChosenLayout,
                        sidebarCollapsed = layoutPrefs.sidebarCollapsed,
                        modernSidebarEnabled = layoutPrefs.modernSidebarEnabled,
                        modernSidebarBlurPref = layoutPrefs.modernSidebarBlurPref,
                        discoverLocation = layoutPrefs.discoverLocation,
                        addonSetupSkipped = extraPrefs.addonSetupSkipped,
                        smoothBringIntoViewEnabled = extraPrefs.smoothBringIntoViewEnabled,
                        fastHorizontalNavigationEnabled = extraPrefs.fastHorizontalNavigationEnabled,
                        composeHighlighterEnabled = extraPrefs.composeHighlighterEnabled,
                        settingsUiStyle = extraPrefs.settingsUiStyle,
                        cardDepthStyle = cardDepthStyle,
                        landscapePosterMode = landscapePosterMode,
                        alwaysBackdropWithLogo = alwaysBackdropWithLogo,
                        logoOverCardTrailer = logoOverCardTrailer
                    )
                }
            }
            val mainUiPrefs by key(activeProfileId) {
                mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))
            }
            val installedAddons by remember(addonRepository) {
                addonRepository.getInstalledAddons()
            }.collectAsState(initial = null)
            val discoverLocation = mainUiPrefs.discoverLocation

            val uiScalePercent by remember {
                com.nuvio.tv.data.local.UiScalePreference.flow(applicationContext)
            }.collectAsState(initial = null)
            val devicePreferences by remember { DeviceUiPreferenceStore.flow(applicationContext) }
                .collectAsState(initial = null)
            val appearancePreferences by remember { V2AppearancePreferenceStore.flow(applicationContext) }
                .collectAsState(initial = null)
            val device = devicePreferences
            val appearance = appearancePreferences
            val originalScale = uiScalePercent
            // Resolve the device snapshot before profile selection's first themed frame.
            if (device == null || appearance == null || originalScale == null) {
                Box(Modifier.fillMaxSize().then(if (startPicture.showing) Modifier else Modifier.background(Color.Black)))
                return@LauncherOnlySemantics
            }
            SideEffect { com.nuvio.tv.core.util.StartupLatencyTrace.coldOnce("prefs_ready") }
            var playbackUiActive by remember { mutableStateOf(false) }
            val profileEntry = remember { com.nuvio.tv.ui.v2.profile.ProfileEntryState() }
            var entryConsumedKey by remember { mutableStateOf<Int?>(null) }
            val canvas = rememberUiCanvasSnapshot()
            val scaleDecision = rememberStableUiScale(canvas, device, originalScale, playbackUiActive)
            val qualityDecision = com.nuvio.tv.ui.v2.quality.rememberDeviceVisualQuality(canvas, device, qualityGovernor)
            NuvioTheme(
                appTheme = mainUiPrefs.theme,
                customThemeColors = mainUiPrefs.customThemeColors,
                appFont = mainUiPrefs.font,
                amoledMode = mainUiPrefs.amoledMode,
                amoledSurfacesMode = mainUiPrefs.amoledSurfacesMode,
                settingsUiStyle = mainUiPrefs.settingsUiStyle,
                uiScalePercent = scaleDecision.percent,
                presentation = ResolvedAppearance(device, appearance, scaleDecision, qualityDecision, playbackUiActive)
            ) {
                SideEffect {
                    com.nuvio.tv.ui.util.RecompositionHighlighterFlag.enabled =
                        BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled
                }
                val artworkAccent = com.nuvio.tv.ui.v2.appearance.rememberArtworkAccent()
                val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
                val bringIntoViewSpec = if (mainUiPrefs.smoothBringIntoViewEnabled) {
                    NuvioScrollDefaults.smoothScrollSpec
                } else {
                    defaultBringIntoViewSpec
                }
                val systemDensity = LocalDensity.current
                val clampedFontScaleDensity = remember(systemDensity) {
                    Density(
                        density = systemDensity.density,
                        fontScale = systemDensity.fontScale.coerceAtMost(MAX_SUPPORTED_FONT_SCALE)
                    )
                }
                CompositionLocalProvider(
                    com.nuvio.tv.ui.v2.appearance.LocalArtworkAccent provides artworkAccent,
                    com.nuvio.tv.ui.v2.profile.LocalProfileEntryState provides profileEntry,
                    LocalDensity provides clampedFontScaleDensity,
                    LocalBringIntoViewSpec provides bringIntoViewSpec,
                    LocalFastHorizontalNavigationEnabled provides mainUiPrefs.fastHorizontalNavigationEnabled,
                    LocalRecompositionHighlighterEnabled provides (BuildConfig.IS_DEBUG_BUILD && mainUiPrefs.composeHighlighterEnabled),
                    LocalCardDepthStyle provides mainUiPrefs.cardDepthStyle,
                    LocalLandscapePosterMode provides mainUiPrefs.landscapePosterMode,
                    LocalAlwaysBackdropWithLogo provides mainUiPrefs.alwaysBackdropWithLogo,
                    LocalLogoOverCardTrailer provides mainUiPrefs.logoOverCardTrailer,
                    LocalMemberAccess provides mainUiPrefs.memberAccess,
                    LocalAmbientIdleController provides ambientIdleController,
                    com.nuvio.tv.core.player.LocalTrailerPlayerPool provides trailerPlayerPool
                ) {
                val transparentPlayerBackdrop = PlayerWindowBackdrop.isTransparentRequested
                Surface(
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.keyCode == entryConsumedKey) {
                            if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) entryConsumedKey = null
                            true
                        } else if (!profileEntry.pending || playbackUiActive) false
                        else when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP,
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                            android.view.KeyEvent.KEYCODE_ENTER -> {
                                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                    entryConsumedKey = event.nativeKeyEvent.keyCode
                                    profileEntry.requestReveal()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = if (transparentPlayerBackdrop) {
                            Color.Transparent
                        } else {
                            NuvioTheme.colors.Background
                        },
                        contentColor = contentColorFor(NuvioTheme.colors.Background)
                    )
                ) {
                    val screensaverVisible by screensaverController.overlayVisible.collectAsState()
                    val screensaverDimPercent by themeDataStore.screensaverDimPercent.collectAsState(
                        initial = ThemeDataStore.DEFAULT_SCREENSAVER_DIM_PERCENT
                    )
                    ScreensaverOverlay(
                        visible = screensaverVisible,
                        dimPercent = screensaverDimPercent
                    )

                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .startPictureOr(startPicture, NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (authState is AuthState.Loading || activeProfileHasPin == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .startPictureOr(startPicture, NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }

                    if (backupPartlyRestored) {
                        com.nuvio.tv.ui.screens.backup.BackupPartialRestorePrompt(
                            onDone = {
                                if (!backupPartialNotice.finish()) {
                                    Toast.makeText(context, context.getString(R.string.backup_restored_reopen), Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }

                    val showBackupSignIn = com.nuvio.tv.core.backup.BackupSignInOffer.shouldShow(
                        backupSignInPrompt, hasSeenAuthQrOnFirstLaunch, authState is AuthState.SignedOut
                    )
                    LaunchedEffect(showBackupSignIn) {
                        if (showBackupSignIn) backupSignInOffer.clear()
                    }
                    if (showBackupSignIn) {
                        com.nuvio.tv.ui.screens.backup.BackupSignInPrompt(
                            onSignIn = {
                                backupSignInPrompt = false
                                lifecycleScope.launch { appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(false) }
                            },
                            onNotNow = { backupSignInPrompt = false }
                        )
                    }

                    if (
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
                    ) {
                        SideEffect { startPicture.end() }
                        AuthQrSignInScreen(
                            onBackPress = { finish() },
                            onContinue = {
                                lifecycleScope.launch {
                                    val shouldRunRemoteOnboardingSync =
                                        authManager.authState.value is AuthState.FullAccount

                                    if (shouldRunRemoteOnboardingSync) {
                                        if (onboardingProfileSyncInProgress) return@launch
                                        onboardingProfileSyncInProgress = true
                                        val maxAttempts = 3
                                        var synced = false
                                        for (attempt in 0 until maxAttempts) {
                                            val result = profileSyncService.pullFromRemote()
                                            if (result.isSuccess) {
                                                synced = true
                                                break
                                            }
                                            if (attempt < maxAttempts - 1) {
                                                delay(1_000)
                                            }
                                        }
                                        if (!synced) {
                                            android.util.Log.w(
                                                "MainActivity",
                                                "Onboarding profile sync failed after retries; continuing"
                                            )
                                        }
                                    }
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                    onboardingProfileSyncInProgress = false
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    val shouldShowProfileSelection =
                        !hasSelectedProfileThisSession && (profiles.size > 1 || activeProfileHasPin == true || v2ProfilePickerRequested)

                    if (shouldShowProfileSelection) {
                        SideEffect { startPicture.end() }
                        ProfileSelectionScreen(
                            onProfileSelected = {
                                v2ProfilePickerRequested = false
                                if (device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2) {
                                    profileEntry.start(appearance.introAnimationEnabled)
                                }
                                hasSelectedProfileThisSession = true
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    // Past sign-in and the profile page, the intro is what comes next: get its art ready while Home loads.
                    val introPalette = NuvioTheme.arcPalette
                    val rootView = LocalView.current
                    LaunchedEffect(introPalette, mainUiPrefs.experienceModeLoaded, device.interfaceExperience, appearance.introAnimationEnabled) {
                        if (mainUiPrefs.experienceModeLoaded && appearance.introAnimationEnabled && !profileEntry.hasRunThisSession &&
                            device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2) {
                            com.nuvio.tv.ui.brand.ArcIntroPrep.warm(context, introPalette, rootView.width, rootView.height)
                        }
                    }
                    val layoutChosen = mainUiPrefs.hasChosenLayout
                    if (layoutChosen == null || !mainUiPrefs.experienceModeLoaded || installedAddons == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .startPictureOr(startPicture, NuvioTheme.colors.Background)
                        )
                        return@Surface
                    }
                    val effectiveExperienceMode = resolveStartExperienceMode(mainUiPrefs.experienceMode, layoutChosen)
                    val needsExperienceSelection = effectiveExperienceMode == null
                    val followsLaunchTargets = launchTargetsReady(mainUiPrefs.experienceMode, layoutChosen)
                    val needsEssentialAddonSetup =
                        effectiveExperienceMode == ExperienceMode.ESSENTIAL &&
                            installedAddons.orEmpty().isEmpty() &&
                            !mainUiPrefs.addonSetupSkipped
                    val pendingDeepLink by pendingDeepLinkUrl.collectAsState()

                    LaunchedEffect(pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        val deepLink = DeepLinkParser.parse(url)
                        if (deepLink is AppDeepLink.AddonInstall && (needsEssentialAddonSetup || !followsLaunchTargets)) {
                            Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                            val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                            if (pendingDeepLinkUrl.value == url) {
                                pendingDeepLinkUrl.value = null
                            }
                            Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                        }
                    }

                    if (needsEssentialAddonSetup) {
                        SideEffect { startPicture.end() }
                        EssentialAddonSetupScreen(
                            onSkip = {
                                lifecycleScope.launch {
                                    experienceModeDataStore.setAddonSetupSkipped(true)
                                }
                            }
                        )
                        return@Surface
                    }
                    // A remembered profile still receives one branded cold-entry handoff.
                    // Explicit profile selection starts it above; ordinary navigation never replays it.
                    // A handoff that loses Home (profiles arriving after the first sign-in) must not keep holding the keys.
                    DisposableEffect(profileEntry) { onDispose { profileEntry.finish() } }
                    LaunchedEffect(device.interfaceExperience) {
                        if (device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2 &&
                            profileEntry.shouldStartOnEntry() && !playbackUiActive) profileEntry.start(appearance.introAnimationEnabled)
                    }
                    val v2Appearance = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current
                    val sidebarCollapsed = v2Appearance?.navigationStyle == com.nuvio.tv.domain.model.NavigationStyle.MINIMAL || mainUiPrefs.sidebarCollapsed
                    val modernSidebarEnabled = v2Appearance != null || mainUiPrefs.modernSidebarEnabled
                    val modernSidebarBlurEnabled = if (com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null) {
                        v2Appearance?.visualStyle == com.nuvio.tv.domain.model.VisualStyle.CINEMATIC_GLASS &&
                            com.nuvio.tv.ui.v2.quality.LocalGlassTokens.current.liveBlur
                    } else mainUiPrefs.modernSidebarBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    val hideBuiltInHeadersForFloatingPill = modernSidebarEnabled && !sidebarCollapsed

                    val startDestination = when {
                        needsExperienceSelection -> Screen.ExperienceModeSelection.route
                        layoutChosen -> Screen.Home.route
                        // The fork has one layout, so the layout picker is bypassed.
                        else -> Screen.Home.route
                    }
                    val navController = rememberNavController()
                    // Back stack ViewModels live in a store of their own that ends with this
                    // navigation subtree, so a profile switch does not leave old screens running.
                    val navViewModelStoreOwner = rememberNavSessionViewModelStoreOwner(navController)
                    val navigationContent = remember(navController, navViewModelStoreOwner, startDestination) {
                        movableContentOf<Boolean> { hideHeaders ->
                            CompositionLocalProvider(
                                LocalViewModelStoreOwner provides navViewModelStoreOwner,
                                LocalNavViewModelStoreOwner provides navViewModelStoreOwner,
                                com.nuvio.tv.ui.screens.calendar.LocalCalendarReminderCenter provides calendarReminders
                            ) {
                                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                                NuvioNavHost(navController, startDestination, hideBuiltInHeaders = hideHeaders)
                                FormatCheckHost(navController)
                                }
                            }
                        }
                    }
                    var optimisticRoute by remember { mutableStateOf<String?>(null) }
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val actualRoute = navBackStackEntry?.destination?.route
                    val currentRoute = optimisticRoute ?: actualRoute
                    val visibleEntries by navController.visibleEntries.collectAsState()
                    LaunchedEffect(actualRoute) {
                        com.nuvio.tv.data.repository.ArcBreadcrumbs.note("screen ${actualRoute?.substringBefore('?') ?: "none"}")
                    }
                    SideEffect {
                        playbackUiActive = isPlayerUiVisible(
                            actualRoute, visibleEntries.map { it.destination.route }
                        )
                        searchKeyOpensSearch = searchButtonOpensSearch(actualRoute, playbackUiActive)
                    }
                    val searchPresses = searchKeyPresses.intValue
                    LaunchedEffect(searchPresses) {
                        if (searchPresses > 0) navigateToDrawerRoute(navController, currentRoute, Screen.Search.route)
                    }
                    DisposableEffect(Unit) {
                        onDispose {
                            playbackUiActive = false
                            qualityGovernor.context(System.nanoTime(), false, null, false, qualityDecision.tier)
                        }
                    }

                    LaunchedEffect(actualRoute) {
                        optimisticRoute = null
                        screensaverController.notifyInteraction()
                    }

                    // Auto-play next episode for EXTERNAL players: the tracker resolves the
                    // next episode and we navigate into the same Screen.Stream auto-play route
                    // the internal onPlaybackEnded path uses. Collected from the root composable
                    // so it survives StreamScreen's self-pop and a process kill (metadata is
                    // recovered from disk and the event replayed).
                    LaunchedEffect(navController) {
                        externalPlaybackTracker.autoPlayNext.collect { next ->
                            if (!externalPlaybackTracker.claimAutoPlayNextNavigation(next)) {
                                return@collect
                            }
                            Log.d(
                                "MainActivity",
                                "autoPlayNext received: S${next.nextSeason}E${next.nextEpisode} " +
                                    "videoId=${next.nextVideoId}; navigating to Stream"
                            )
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = next.nextVideoId,
                                    contentType = next.contentType,
                                    title = next.contentName,
                                    poster = next.poster,
                                    backdrop = next.backdrop,
                                    logo = next.logo,
                                    season = next.nextSeason,
                                    episode = next.nextEpisode,
                                    year = next.year,
                                    contentId = next.contentId,
                                    contentName = next.contentName,
                                    returnToDetailOnBack = next.contentType.equals("series", ignoreCase = true),
                                    profileId = next.profileId
                                )
                            ) {
                                // Replace any lingering Stream screen (e.g. the previous
                                // episode's, restored from the backstack after a process restart).
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // A watch party guest is taken to the title the host is playing.
                    LaunchedEffect(navController) {
                        partyRuntime.mediaRequest.collect { media ->
                            if (media == null) return@collect
                            partyRuntime.consumeMediaRequest()
                            // A player that opened from the stream screen has already replaced it; close that player
                            // instead of stacking a second one on top.
                            val inPlayer = runCatching { navController.getBackStackEntry(Screen.Player.route) }.isSuccess
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = media.videoId ?: media.contentId,
                                    contentType = media.contentType,
                                    title = media.title,
                                    poster = media.poster,
                                    backdrop = media.backdrop,
                                    logo = media.logo,
                                    season = media.season,
                                    episode = media.episode,
                                    episodeName = media.episodeTitle,
                                    contentId = media.contentId,
                                    contentName = media.title
                                )
                            ) {
                                popUpTo(if (inPlayer) Screen.Player.route else Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    // Navigate to content when launched from the Continue Watching channel row.
                    LaunchedEffect(navController) {
                        if (launchContentId != null && launchContentType != null && followsLaunchTargets) {
                            if (launchMode == "stream" && launchVideoId != null && launchName != null) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = launchVideoId,
                                        contentType = launchContentType,
                                        title = launchName,
                                        poster = launchPoster,
                                        backdrop = launchBackdrop,
                                        logo = launchLogo,
                                        season = launchSeason,
                                        episode = launchEpisode,
                                        episodeName = launchEpisodeTitle,
                                        contentId = launchContentId,
                                        contentName = launchName,
                                        returnToDetailOnBack = launchContentType.equals("series", ignoreCase = true),
                                        returnToHomeOnBack = true
                                    )
                                )
                            } else {
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = launchContentId,
                                        itemType = launchContentType
                                    )
                                )
                            }
                        }
                    }

                    val pendingLaunch by pendingLaunchIntent.collectAsState()
                    LaunchedEffect(navController, followsLaunchTargets, pendingLaunch) {
                        val intent = pendingLaunch ?: return@LaunchedEffect
                        if (!followsLaunchTargets) return@LaunchedEffect
                        pendingLaunchIntent.value = null
                        val contentId = intent.getStringExtra("contentId") ?: return@LaunchedEffect
                        val contentType = intent.getStringExtra("contentType") ?: return@LaunchedEffect
                        val videoId = intent.getStringExtra("videoId")
                        val name = intent.getStringExtra("name")
                        if (videoId != null && name != null) {
                            navController.navigate(
                                Screen.Stream.createRoute(
                                    videoId = videoId,
                                    contentType = contentType,
                                    title = name,
                                    poster = intent.getStringExtra("poster"),
                                    backdrop = intent.getStringExtra("backdrop"),
                                    logo = intent.getStringExtra("logo"),
                                    season = intent.getIntExtra("season", -1).takeIf { it >= 0 },
                                    episode = intent.getIntExtra("episode", -1).takeIf { it >= 0 },
                                    episodeName = intent.getStringExtra("episodeTitle"),
                                    contentId = contentId,
                                    contentName = name,
                                    returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                                    returnToHomeOnBack = true
                                )
                            )
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType
                                )
                            )
                        }
                    }

                    LaunchedEffect(navController, followsLaunchTargets, pendingDeepLink) {
                        val url = pendingDeepLink ?: return@LaunchedEffect
                        if (!followsLaunchTargets) return@LaunchedEffect
                        when (val deepLink = DeepLinkParser.parse(url)) {
                            is AppDeepLink.Meta -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(
                                    Screen.Detail.createRoute(
                                        itemId = deepLink.id,
                                        itemType = deepLink.type
                                    )
                                ) {
                                    launchSingleTop = true
                                }
                            }
                            AppDeepLink.ScoutAsk -> {
                                pendingDeepLinkUrl.value = null
                                navController.navigate(Screen.ScoutAsk.route) {
                                    launchSingleTop = true
                                }
                            }
                            is AppDeepLink.AddonInstall -> {
                                navController.navigate(Screen.AddonManager.route) {
                                    launchSingleTop = true
                                }
                                Toast.makeText(context, context.getString(R.string.addon_installing), Toast.LENGTH_SHORT).show()
                                val installResult = deepLinkHandler.installAddon(deepLink.manifestUrl)
                                if (pendingDeepLinkUrl.value == url) {
                                    pendingDeepLinkUrl.value = null
                                }
                                Toast.makeText(context, installResult.message, Toast.LENGTH_LONG).show()
                            }
                            null -> {
                                pendingDeepLinkUrl.value = null
                            }
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    LaunchedEffect(discoverLocation, currentRoute) {
                        if (discoverLocation == null) return@LaunchedEffect
                        val onDiscoverRoute = currentRoute == Screen.Discover.route ||
                            currentRoute?.startsWith("${Screen.Discover.route}/") == true
                        if (discoverLocation == DiscoverLocation.OFF && onDiscoverRoute) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = false }
                                launchSingleTop = true
                            }
                        }
                    }

                    val calendarInMenuFlow = remember {
                        calendarSettingsDataStore.settings
                            .map { com.nuvio.tv.ui.screens.calendar.calendarMenuVisible(it) }
                            .distinctUntilChanged()
                    }
                    val calendarInMenu by calendarInMenuFlow.collectAsState(
                        initial = com.nuvio.tv.ui.screens.calendar.calendarMenuVisible(calendarSettingsDataStore.settings.value)
                    )
                    val rootRoutes = remember(discoverLocation, calendarInMenu) {
                        appRootRoutes(discoverInSidebar = discoverLocation == DiscoverLocation.IN_SIDEBAR, calendarInMenu = calendarInMenu)
                    }

                    SideEffect {
                        qualityGovernor.context(
                            now = System.nanoTime(),
                            allowed = device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2 &&
                                device.visualQualityMode == com.nuvio.tv.domain.model.VisualQualityMode.AUTOMATIC &&
                                actualRoute in rootRoutes &&
                                !isPlayerUiVisible(actualRoute, visibleEntries.map { it.destination.route }) &&
                                canvas.refreshRateHz?.let { it in 58f..62f } == true,
                            destination = actualRoute,
                            stableUtility = actualRoute == Screen.Settings.route,
                            currentTier = qualityDecision.tier
                        )
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavDiscover = stringResource(R.string.nav_discover)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val strNavCalendar = stringResource(R.string.nav_calendar)
                    val strNavScout = stringResource(R.string.scout_title)
                    val drawerItems = remember(
                        strNavHome,
                        strNavDiscover,
                        strNavSearch,
                        strNavLibrary,
                        strNavSettings,
                        strNavCalendar,
                        discoverLocation,
                        calendarInMenu
                    ) {
                        buildList {
                            add(
                                DrawerItem(
                                    route = Screen.Home.route,
                                    label = strNavHome,
                                    icon = Icons.Default.Home
                                )
                            )
                            if (discoverLocation == DiscoverLocation.IN_SIDEBAR) {
                                add(
                                    DrawerItem(
                                        route = Screen.Discover.route,
                                        label = strNavDiscover,
                                        icon = Icons.Default.Explore
                                    )
                                )
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Search.route,
                                    label = strNavSearch,
                                    iconRes = R.raw.sidebar_search
                                )
                            )
                            if (com.nuvio.tv.domain.model.ScoutFeature.available) {
                                add(DrawerItem(route = Screen.Scout.route, label = strNavScout, iconRes = R.raw.sidebar_scout))
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Library.route,
                                    label = strNavLibrary,
                                    iconRes = R.raw.sidebar_library
                                )
                            )
                            if (calendarInMenu) {
                                add(
                                    DrawerItem(
                                        route = Screen.Calendar.route,
                                        label = strNavCalendar,
                                        icon = Icons.Default.CalendarMonth
                                    )
                                )
                            }
                            add(
                                DrawerItem(
                                    route = Screen.Settings.route,
                                    label = strNavSettings,
                                    iconRes = R.raw.sidebar_settings
                                )
                            )
                        }
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    val confirmExitEnabled by profileManager.confirmExitEnabled.collectAsState()
                    var backPressedOnce by remember { mutableStateOf(false) }
                    LaunchedEffect(backPressedOnce) {
                        if (backPressedOnce) {
                            delay(2000L)
                            backPressedOnce = false
                        }
                    }
                    val handleExitApp: () -> Unit = {
                        if (!confirmExitEnabled || backPressedOnce) {
                            finishAffinity()
                            finishAndRemoveTask()
                            if (confirmExitEnabled) {
                                // Kill the process to free RAM on low-memory devices.
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        } else {
                            backPressedOnce = true
                            Toast.makeText(this@MainActivity, getString(R.string.confirm_exit_toast), Toast.LENGTH_SHORT).show()
                        }
                    }

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()
                    val updateBannerState = updateState.copy(
                        showBanner = updateState.showBanner && currentRoute?.startsWith("player/") != true
                    )

                    UpdateBannerHost(
                        state = updateBannerState,
                        onDismissBanner = updateViewModel::dismissBanner,
                        onDownload = updateViewModel::downloadUpdate,
                        onInstall = updateViewModel::installUpdateOrRequestPermission,
                        onDismissUnknownSources = updateViewModel::dismissUnknownSourcesDialog,
                        onOpenUnknownSources = updateViewModel::openUnknownSourcesSettings,
                        onFeedbackShown = updateViewModel::consumeFeedbackMessage
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Home can compose and warm images without drawing underneath
                            // the opaque ident. Reveal its layer only when the fade begins.
                            Box(Modifier.fillMaxSize().graphicsLayer {
                                alpha = if (profileEntry.pending && !profileEntry.revealing) 0f else 1f
                            }) {
                            if (v2Appearance?.navigationStyle == com.nuvio.tv.domain.model.NavigationStyle.TOP_NAVIGATION) {
                                com.nuvio.tv.ui.v2.navigation.V2TopNavigation(
                                    navigationContent = navigationContent,
                                    currentRoute = currentRoute, rootRoutes = rootRoutes, items = drawerItems,
                                    selectedRoute = selectedDrawerRoute,
                                    profile = activeProfile?.let {
                                        com.nuvio.tv.ui.v2.components.AvatarModel(it.id.toString(), it.name, it.avatarColorHex, activeProfileAvatarImageUrl)
                                    },
                                    onNavigate = { route ->
                                        optimisticRoute = route
                                        navigateToDrawerRoute(navController, currentRoute, route)
                                    },
                                    onSwitchProfile = {
                                        v2ProfilePickerRequested = device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2
                                        hasSelectedProfileThisSession = false
                                    },
                                    onExit = handleExitApp
                                )
                            } else if (modernSidebarEnabled) {
                                ModernSidebarScaffold(
                                    longPressBackHeld = longPressBackHeld,
                                    navController = navController,
                                    navigationContent = navigationContent,
                                    currentRoute = currentRoute,
                                    detailPageLeaving = com.nuvio.tv.ui.navigation.isDetailPageLeaving(
                                        actualRoute, visibleEntries.map { it.destination.route }
                                    ),
                                    rootRoutes = rootRoutes,
                                    drawerItems = drawerItems,
                                    selectedDrawerRoute = selectedDrawerRoute,
                                    selectedDrawerItem = selectedDrawerItem,
                                    sidebarCollapsed = sidebarCollapsed,
                                    modernSidebarBlurEnabled = modernSidebarBlurEnabled,
                                    hideBuiltInHeaders = hideBuiltInHeadersForFloatingPill,
                                    activeProfileName = activeProfile?.name ?: "",
                                    activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                                    activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                    showProfileSelector = activeProfile != null,
                                    onSwitchProfile = {
                                        v2ProfilePickerRequested = device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2
                                        hasSelectedProfileThisSession = false
                                    },
                                    onNavigate = { optimisticRoute = it },
                                    onExitApp = handleExitApp
                                )
                            } else {
                                LegacySidebarScaffold(
                                    longPressBackHeld = longPressBackHeld,
                                    navController = navController,
                                    navigationContent = navigationContent,
                                    currentRoute = currentRoute,
                                    rootRoutes = rootRoutes,
                                    drawerItems = drawerItems,
                                    selectedDrawerRoute = selectedDrawerRoute,
                                    sidebarCollapsed = sidebarCollapsed,
                                    hideBuiltInHeaders = false,
                                    activeProfileName = activeProfile?.name ?: "",
                                    activeProfileColorHex = activeProfile?.avatarColorHex ?: "#1E88E5",
                                    activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                                    showProfileSelector = activeProfile != null,
                                    onSwitchProfile = {
                                        v2ProfilePickerRequested = device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2
                                        hasSelectedProfileThisSession = false
                                    },
                                    onNavigate = { optimisticRoute = it },
                                    onExitApp = handleExitApp
                                )
                            }

                            }
                            val autoNextOverlay by externalPlaybackTracker.autoNextOverlay.collectAsState()
                            autoNextOverlay?.let { ov ->
                                com.nuvio.tv.ui.screens.player.LoadingOverlay(
                                    visible = true,
                                    backdropUrl = ov.backdrop,
                                    logoUrl = ov.logo,
                                    title = ov.title,
                                    message = ov.message ?: stringResource(R.string.external_auto_next_loading),
                                    progress = ov.progress,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (!profileEntry.pending) {
                                com.nuvio.tv.ui.screens.party.PartyFriendOverlays(partyRuntime, onShown = screensaverController::notifyWake)
                            }
                            com.nuvio.tv.ui.screens.calendar.CalendarReminderHost(
                                center = calendarReminders,
                                covered = profileEntry.pending || screensaverVisible,
                                playerVisible = playbackUiActive,
                                currentRoute = actualRoute,
                                resolveWatch = { reminder -> calendarEpisodeResolver.get().resolve(reminder) },
                                onWatchTarget = { target -> navController.openCalendarWatchTarget(target) },
                                onOpenTitle = { reminder ->
                                    val ids = com.nuvio.tv.core.calendar.calendarContentIds(reminder.title)
                                    ids.firstOrNull()?.let { id ->
                                        val film = reminder.title.type == com.nuvio.tv.core.calendar.CalendarTitleType.MOVIE
                                        navController.navigate(Screen.Detail.createRoute(id, if (film) "movie" else "series"))
                                    }
                                },
                                onSeeAll = {
                                    calendarReminders.requestCalendarAtJustAired()
                                    optimisticRoute = Screen.Calendar.route
                                    navigateToDrawerRoute(navController, currentRoute, Screen.Calendar.route)
                                }
                            )
                            LaunchedEffect(currentRoute, profileEntry.pending) {
                                if (currentRoute != null && currentRoute != Screen.Home.route) profileEntry.finish()
                            }
                            if (profileEntry.pending && !playbackUiActive) {
                                com.nuvio.tv.ui.v2.profile.ProfileEntryIdent(
                                    profileEntry,
                                    soundEnabled = appearance.introSoundEnabled,
                                    startPicture = startPicture
                                ) {
                                    v2ProfilePickerRequested = true
                                    hasSelectedProfileThisSession = false
                                }
                            } else if (
                                startPicture.showing && com.nuvio.tv.ui.v2.profile.EntryIdentPolicy.introComing(
                                    pending = profileEntry.pending,
                                    nuvioV2 = device.interfaceExperience == com.nuvio.tv.domain.model.InterfaceExperience.NUVIO_V2,
                                    introEnabled = appearance.introAnimationEnabled,
                                    startsOnEntry = profileEntry.shouldStartOnEntry(),
                                    playbackUiActive = playbackUiActive
                                )
                            ) {
                                // The ident starts with the next frame: keep the start window's mark over Home until then.
                                Box(Modifier.fillMaxSize().startPicture(startPicture))
                            } else {
                                SideEffect { startPicture.end() }
                            }

                            val isTrailerPlaying by trailerPlayerPool.isTrailerPlaying.collectAsState()
                            val playbackActive by screensaverController.playbackActive.collectAsState()
                            val ambientShowing by ambientIdleController.isShowing.collectAsState()
                            val dimWhenIdle by themeDataStore.screensaverEnabled.collectAsState(initial = true)
                            LaunchedEffect(isTrailerPlaying) {
                                if (!isTrailerPlaying) screensaverController.notifyInteraction()
                            }
                            LaunchedEffect(ambientShowing) {
                                if (!ambientShowing && !screensaverController.overlayVisible.value) {
                                    trailerPlayerPool.setScreensaverSuppressed(false)
                                }
                            }
                            AmbientModeHost(
                                controller = ambientIdleController,
                                allowed = !playbackUiActive &&
                                    !playbackActive &&
                                    autoNextOverlay == null &&
                                    !isTrailerPlaying &&
                                    !profileEntry.pending &&
                                    (AMBIENT_BEFORE_OLED_DIM || !dimWhenIdle),
                                onShown = {
                                    trailerPlayerPool.setScreensaverSuppressed(true)
                                    trailerPlayerPool.stop()
                                }
                            )
                            if (com.nuvio.tv.core.build.IssueReports.available) {
                                com.nuvio.tv.ui.screens.settings.CrashPromptHost(
                                    canShow = actualRoute == Screen.Home.route &&
                                        !playbackUiActive &&
                                        !playbackActive &&
                                        autoNextOverlay == null &&
                                        !profileEntry.pending &&
                                        !screensaverVisible &&
                                        !ambientShowing &&
                                        !updateState.showBanner,
                                    lastInteractionAt = screensaverController.lastInteractionAt
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        com.nuvio.tv.ui.v2.diagnostics.UiFrameDiagnostics.frames.clear()
        jankStats = JankStats.createAndTrack(window) { frameData ->
            qualityGovernor.frame(System.nanoTime(), frameData.isJank)
            com.nuvio.tv.ui.v2.diagnostics.UiFrameDiagnostics.frames.record(
                System.nanoTime(), frameData.frameDurationUiNanos, frameData.isJank
            )
            if (frameData.isJank) {
                com.nuvio.tv.data.repository.ArcSlowFrames.note(
                    frameData.frameDurationUiNanos / 1_000_000,
                    frameData.states.firstOrNull { it.key == "Screen" }?.value
                )
                Log.w(
                    "JankStats",
                    "JANK: ${frameData.frameDurationUiNanos / 1_000_000}ms | states: ${frameData.states}"
                )
            }
        } }
    }

    private val hideSemanticsTree = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        hideSemanticsTree.value = com.nuvio.tv.core.performance.launcherOnlySemanticsTest(this)
        ambientIdleController.dismiss()
        qualityGovernor.resume(System.nanoTime())
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        memberAccessRepository.refreshIfStale()
        lifecycleScope.launch {
            deviceSessionRegistration.requestForegroundRegistration()
            startupSyncService.requestForegroundSync()
        }
        lifecycleScope.launch {
            if (isFirstResumeAfterCreate) {
                isFirstResumeAfterCreate = false
                // Home loads first; then only the trackers that are due refresh, one after another.
                delay(FIRST_TRACKER_REFRESH_DELAY_MS)
                trackingProgressRefreshCoordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC, oneAtATime = true)
            } else {
                trackingProgressRefreshCoordinator.refreshConnected(TrackingRefreshIntent.AUTOMATIC)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLinkIntent(intent)
        captureLaunchIntent(intent)
    }

    private fun captureDeepLinkIntent(intent: Intent?) {
        val url = intent?.dataString?.trim()?.takeIf(String::isNotBlank) ?: return
        pendingDeepLinkUrl.value = url
    }

    private fun captureLaunchIntent(intent: Intent?) {
        val contentId = intent?.getStringExtra("contentId") ?: return
        val launchMode = intent.getStringExtra("launchMode") ?: return
        if (launchMode != "stream") return
        pendingLaunchIntent.value = intent
    }

    override fun onPause() {
        super.onPause()
        qualityGovernor.pause(System.nanoTime())
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    // Intercept Back at the Activity level, before any Compose BackHandler, so the auto-next loader
    // can always be dismissed. Compose back-dispatch ordering kept putting the destination screen's
    // handler above the loader's, so Back never reached it.
    // Tracks whether a long-press Back sequence is in progress. When true, all Back
    // key events are consumed at the Activity level so that repeated DOWN events from a
    // held Back key don't cascade through Compose BackHandlers (e.g. opening the sidebar
    // and then immediately exiting the app).
    val longPressBackHeld = mutableStateOf(false)

    override fun onUserInteraction() {
        super.onUserInteraction()
        screensaverController.notifyInteraction()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            qualityGovernor.input(System.nanoTime(), event.keyCode, event.action == KeyEvent.ACTION_DOWN)
        }
        // Idle overlays (OLED dimmer, Ambient Mode): while one is visible, the first key
        // press wakes it and the whole press (down, repeats, and the matching up) is
        // swallowed so waking never also navigates. Stray key-ups after wake are swallowed
        // too, so ACTION_UP handlers below never see an up without its down.
        if (screensaverController.overlayVisible.value || ambientIdleController.isShowing.value) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                screensaverController.notifyWake()
                ambientIdleController.dismiss()
                swallowKeysUntilUp = true
            }
            return true
        }
        if (swallowKeysUntilUp) {
            if (event.action == KeyEvent.ACTION_UP) {
                swallowKeysUntilUp = false
            }
            return true
        }
        screensaverController.notifyInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && calendarReminders.onMenuKey()) {
                calendarMenuKeyDown = true
                return true
            }
            if (calendarMenuKeyDown) {
                if (event.action == KeyEvent.ACTION_UP) calendarMenuKeyDown = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH && searchKeyOpensSearch) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) searchKeyPresses.intValue++
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (longPressBackHeld.value) {
                if (event.action == KeyEvent.ACTION_UP) longPressBackHeld.value = false
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            externalPlaybackTracker.autoNextOverlay.value != null
        ) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.d("ExtAutoNext", "dispatchKeyEvent BACK -> dismissAutoNextOverlay (loader showing)")
                externalPlaybackTracker.dismissAutoNextOverlay()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Compose Dialogs are separate platform windows: their keys never reach this
        // Activity, so idle detection is blind while one is focused. Pause screensaver
        // eligibility while this window lacks focus; refocus restarts the idle clock.
        screensaverController.setWindowFocused(hasFocus)
        if (!hasFocus) calendarMenuKeyDown = false
        if (hasFocus) hideSystemBarsOnHandheld()
    }

    /** Handhelds and tablets keep Android's bars on screen; a swipe from the edge brings them back. TV boxes have none. */
    private fun hideSystemBarsOnHandheld() {
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return
        val currentWindow = window ?: return
        androidx.core.view.WindowCompat.getInsetsController(currentWindow, currentWindow.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        // Returning from an external player: raise the auto-next loader before the player's
        // result is dispatched and before the window repaints, so the transition shows the
        // loader instantly with no episode-list flash. No-op unless a series episode is being
        // tracked; onActivityResult keeps it for a completion or dismisses it otherwise.
        externalPlaybackTracker.raiseAutoNextOverlayOnReturn()
        super.onStart()
        startupSyncService.startPeriodicSurfacePulls()
        androidTvChannelSyncService.onForegroundChanged(true)
        partyRuntime.setAppVisible(true)
        calendarReminders.setAppVisible(true)
        calendarAppVisibility.visible.value = true
    }

    override fun onStop() {
        externalPlaybackTracker.onExternalPlayerCoveredApp()
        super.onStop()
        startupSyncService.stopPeriodicSurfacePulls()
        androidTvChannelSyncService.onForegroundChanged(false)
        partyRuntime.setAppVisible(false)
        calendarReminders.setAppVisible(false)
        calendarAppVisibility.visible.value = false
        calendarMenuKeyDown = false
    }

    override fun onDestroy() {
        super.onDestroy()
        PluginRuntimeHooks.onActivityDestroy()
    }
}

/** Test switch: see [com.nuvio.tv.core.performance.launcherOnlySemanticsTest]. */
@Composable
private fun LauncherOnlySemantics(hide: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().then(if (hide) Modifier.clearAndSetSemantics {} else Modifier),
        propagateMinConstraints = true
    ) { content() }
}

@Composable
private fun SidebarFocusRecoveryEffect(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    drawerItemFocusRequesters: Map<String, FocusRequester>,
    sidebarOwnsFocus: Boolean
) {
    LaunchedEffect(drawerItems, sidebarOwnsFocus, selectedDrawerRoute) {
        if (!sidebarOwnsFocus) {
            return@LaunchedEffect
        }
        if (selectedDrawerRoute != null && drawerItems.any { it.route == selectedDrawerRoute }) {
            return@LaunchedEffect
        }
        val fallbackRoute = drawerItems.firstOrNull()?.route ?: return@LaunchedEffect
        val requester = drawerItemFocusRequesters[fallbackRoute] ?: return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LegacySidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    sidebarCollapsed: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val showSidebar = currentRoute in rootRoutes

    LaunchedEffect(currentRoute) {
        longPressBackHeld.value = false
        drawerState.setValue(DrawerValue.Closed)
    }

    val sidebarTokens = NuvioComponents.tokens.sidebar
    val closedDrawerWidth = if (sidebarCollapsed) NuvioTheme.spacing.none else sidebarTokens.legacyCollapsedWidth
    val openDrawerWidth = sidebarTokens.legacyExpandedWidth
    val openDrawerItemWidth = sidebarTokens.itemWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    // Bumped on every key event the drawer sees so the auto-collapse timer
    // resets while the user navigates between drawer items.
    var legacyDrawerInteractionVersion by remember { mutableStateOf(0) }


    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Closed) {
        pendingSidebarFocusRequest = true
        drawerState.setValue(DrawerValue.Open)
    }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Open) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(drawerState.currentValue, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || drawerState.currentValue != DrawerValue.Closed) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && drawerState.currentValue == DrawerValue.Open
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            if (showSidebar) {
                val drawerWidth = if (drawerValue == DrawerValue.Open) openDrawerWidth else closedDrawerWidth
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .background(NuvioTheme.colors.Background)
                        .padding(NuvioTheme.spacing.card.outer)
                        .selectableGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                legacyDrawerInteractionVersion++
                            }
                            val closeKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                            if (keyEvent.key == closeKey && keyEvent.type == KeyEventType.KeyDown) {
                                drawerState.setValue(DrawerValue.Closed)
                                pendingContentFocusTransfer = false
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    val isExpanded = drawerValue == DrawerValue.Open
                    val itemWidth by animateDpAsState(
                        targetValue = if (isExpanded) openDrawerItemWidth else NuvioTheme.sizes.avatars.md,
                        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast, easing = NuvioMotion.tokens.easings.standard),
                        label = "legacySidebarItemWidth"
                    )

                    if (isExpanded) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                        ) {
                            Spacer(modifier = Modifier.height(30.dp))
                            if (showProfileSelector && activeProfileName.isNotEmpty()) {
                                var isProfileFocused by remember { mutableStateOf(false) }
                                val profileItemShape = NuvioTheme.shapes.navItem
                                val profileLeadingInset = NuvioTheme.spacing.lg + NuvioTheme.spacing.xxs
                                val profileAvatarSize = NuvioTheme.sizes.sidebar.leadingVisual
                                val profileLabelStart = 60.dp
                                val profileGapAfterAvatar =
                                    (profileLabelStart - profileLeadingInset - profileAvatarSize).coerceAtLeast(NuvioTheme.spacing.none)
                                val profileBgColor = if (isProfileFocused) NuvioTheme.colors.FocusBackground else Color.Transparent
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .width(itemWidth)
                                            .height(sidebarTokens.itemHeight)
                                            .background(color = profileBgColor, shape = profileItemShape)
                                            .onFocusChanged { isProfileFocused = it.isFocused }
                                            .clickable {
                                                onSwitchProfile()
                                                drawerState.setValue(DrawerValue.Closed)
                                            }
                                            .semantics { role = Role.Button },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Spacer(modifier = Modifier.width(profileLeadingInset))
                                        ProfileAvatarCircle(
                                            name = activeProfileName,
                                            colorHex = activeProfileColorHex,
                                            size = profileAvatarSize,
                                            avatarImageUrl = activeProfileAvatarImageUrl,
                                            imageCrossfade = false
                                        )
                                        Spacer(modifier = Modifier.width(profileGapAfterAvatar))
                                        Text(
                                            text = activeProfileName,
                                            color = if (isProfileFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Start,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else {
                                BrandWordmark(
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(y = 28.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        drawerItems.forEach { item ->
                            key(item.route) {
                                LegacySidebarButton(
                                    label = item.label,
                                    iconRes = item.iconRes,
                                    icon = item.icon,
                                    selected = selectedDrawerRoute == item.route,
                                    expanded = isExpanded,
                                    onClick = {
                                        keyboardController?.hide()
                                        onNavigate(item.route)
                                        navigateToDrawerRoute(
                                            navController = navController,
                                            currentRoute = currentRoute,
                                            targetRoute = item.route
                                        )
                                        drawerState.setValue(DrawerValue.Closed)
                                        pendingContentFocusTransfer = currentRoute == item.route
                                    },
                                    modifier = Modifier.focusRequester(
                                        drawerItemFocusRequesters.getValue(item.route)
                                    )
                                        .width(itemWidth)
                                        .offset(x = NuvioTheme.spacing.md)
                                )
                        }
                    }
                }
            }
        }
        }
    ) {
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar && !sidebarCollapsed) {
                NuvioLayout.tokens.sidebarContentOffset
            } else {
                NuvioTheme.spacing.none
            },
            animationSpec = tween(NuvioMotion.tokens.durations.medium),
            label = "contentStartPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            drawerState.currentValue == DrawerValue.Closed &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                pendingSidebarFocusRequest = true
                                drawerState.setValue(DrawerValue.Open)
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .onKeyEvent { keyEvent ->
                    val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                    if (
                        showSidebar &&
                        drawerState.currentValue == DrawerValue.Closed &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == openKey
                    ) {
                        if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
                            true
                        } else {
                            pendingSidebarFocusRequest = true
                            drawerState.setValue(DrawerValue.Open)
                            true
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides (drawerState.currentValue == DrawerValue.Open),
                LocalContentFocusRequester provides contentFocusRequester
            ) {
                navigationContent(hideBuiltInHeaders)
            }
        }
    }
}

@Composable
private fun LegacySidebarButton(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val itemShape = NuvioTheme.shapes.navItem
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.FocusBackground
            expanded && selected -> NuvioTheme.colors.Secondary
            else -> Color.Transparent
        },
        label = "legacySidebarItemBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.TextPrimary
            expanded && selected -> NuvioTheme.colors.OnSecondary
            else -> NuvioTheme.colors.TextSecondary
        },
        label = "legacySidebarItemContent"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isFocused -> NuvioTheme.colors.TextPrimary
            expanded && selected -> NuvioTheme.colors.OnSecondary
            selected -> NuvioTheme.colors.Secondary
            !expanded -> NuvioTheme.colors.TextTertiary
            else -> NuvioTheme.colors.TextSecondary
        },
        label = "legacySidebarItemIconTint"
    )
    val selectedCollapsedIconBrush = if (selected && !expanded) {
        NuvioTheme.palette.accentBrush()
    } else {
        null
    }
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused && expanded) 1.1f else 1f,
        animationSpec = tween(durationMillis = NuvioMotion.tokens.durations.fast, easing = NuvioMotion.tokens.easings.standard),
        label = "legacySidebarItemScale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .height(NuvioComponents.tokens.sidebar.itemHeight)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
                transformOrigin = TransformOrigin.Center
            }
            .focusProperties { canFocus = expanded }
            .onFocusChanged { isFocused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor,
        ),
        border = CardDefaults.border(
            border = androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(NuvioStrokes.tokens.thin, Color.Transparent),
                shape = itemShape
            )
        ),
        shape = CardDefaults.shape(shape = itemShape),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = label
                    role = Role.Tab
                    this.selected = selected
                }
        ) {
        DrawerItemIcon(
            iconRes = iconRes,
            icon = icon,
            tint = iconTint,
            brush = selectedCollapsedIconBrush,
            modifier = Modifier
                .size(NuvioComponents.tokens.sidebar.iconSize)
                .align(Alignment.CenterStart)
                .offset(x = 13.dp)
        )
        if (expanded) {
            com.nuvio.tv.ui.components.AutoResizeText(
                text = label,
                color = contentColor,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 54.dp, end = 14.dp)
            )
        }
    }
}
}

@Composable
private fun ModernSidebarScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    navigationContent: @Composable (Boolean) -> Unit,
    currentRoute: String?,
    detailPageLeaving: Boolean,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    selectedDrawerItem: DrawerItem,
    sidebarCollapsed: Boolean,
    modernSidebarBlurEnabled: Boolean,
    hideBuiltInHeaders: Boolean,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val showSidebar = currentRoute in rootRoutes
    val sidebarTokens = NuvioComponents.tokens.sidebar
    val isV2Sidebar = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null
    val collapsedSidebarWidth = if (sidebarCollapsed) NuvioTheme.spacing.none else sidebarTokens.collapsedWidth
    val openSidebarWidth = sidebarTokens.expandedWidth

    val focusManager = LocalFocusManager.current
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val contentFocusRequester = remember { FocusRequester() }
    val drawerItemFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var pendingDrawerDestination by remember { mutableStateOf<String?>(null) }
    var cancellingDrawerDestination by remember { mutableStateOf(false) }
    val drawerCloseDuration = NuvioMotion.tokens.durations.sidebarPanelOut
    LaunchedEffect(pendingDrawerDestination) {
        val target = pendingDrawerDestination ?: return@LaunchedEffect
        // Let the existing screen settle before composing the next destination.
        // In particular Settings must not load while Home and its blur are sliding.
        delay(drawerCloseDuration.toLong() + 32L)
        onNavigate(target)
        navigateToDrawerRoute(navController, currentRoute, target)
        pendingDrawerDestination = null
    }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    var pillExpandRequestCount by remember { mutableIntStateOf(0) }
    val keepFloatingPillExpanded = false
    val keepSidebarFocusDuringCollapse =
        isSidebarExpanded || sidebarCollapsePending || pendingContentFocusTransfer
    val hasSidebarProfileItem = showProfileSelector && activeProfileName.isNotEmpty()
    val sidebarTopBoundaryIndex = if (hasSidebarProfileItem) drawerItems.size else 0

    LaunchedEffect(showSidebar) {
        if (!showSidebar) {
            isSidebarExpanded = false
            sidebarCollapsePending = false
            pendingContentFocusTransfer = false
            pendingSidebarFocusRequest = false
            isFloatingPillIconOnly = false
        }
    }

    // Collapse sidebar when navigating between root routes (e.g. Settings -> Home via Back)
    LaunchedEffect(currentRoute) {
        if (isSidebarExpanded && showSidebar) {
            sidebarCollapsePending = true
        }
    }

    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar || keepFloatingPillExpanded) {
            isFloatingPillIconOnly = false
        }
    }

    // Expand pill label briefly after navigating to a different root route
    LaunchedEffect(selectedDrawerRoute) {
        if (showSidebar && !isSidebarExpanded) {
            isFloatingPillIconOnly = false
            pillExpandRequestCount++
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        isSidebarExpanded = true
        sidebarCollapsePending = false
        pendingSidebarFocusRequest = true
    }

    BackHandler(enabled = currentRoute in rootRoutes && isSidebarExpanded && !sidebarCollapsePending) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    LaunchedEffect(sidebarCollapsePending, isSidebarExpanded, showSidebar) {
        if (!showSidebar || !sidebarCollapsePending) {
            return@LaunchedEffect
        }
        if (!isSidebarExpanded) {
            sidebarCollapsePending = false
            return@LaunchedEffect
        }
        delay(95L)
        isSidebarExpanded = false
        sidebarCollapsePending = false
    }

    // Auto-collapse the expanded sidebar after a short period of inactivity.
    // The timer resets every time focus moves between drawer items, so the

    // Auto-collapse the floating pill back to icon-only when the user reveals
    // its label (DPAD UP from content) and then leaves it idle. The DPAD DOWN
    // path already collapses it instantly, this just covers the case where the
    // user releases UP and walks away.
    LaunchedEffect(isFloatingPillIconOnly, keepFloatingPillExpanded, showSidebar, isSidebarExpanded, pillExpandRequestCount) {
        if (!showSidebar || isFloatingPillIconOnly || keepFloatingPillExpanded || isSidebarExpanded) {
            return@LaunchedEffect
        }
        delay(SIDEBAR_AUTO_COLLAPSE_DELAY_MS)
        isFloatingPillIconOnly = true
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val fallbackSidebarHazeState = remember { HazeState() }
    val sharedSidebarHazeState = com.nuvio.tv.ui.v2.components.LocalGlassBackdrop.current
    val v2Appearance = com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current
    // V2 rows sit straight on the page with an adaptive shade; Pure Liquid Dark keeps its solid panel.
    val menuOnPicture = isV2Sidebar &&
        v2Appearance?.visualStyle != com.nuvio.tv.domain.model.VisualStyle.PURE_LIQUID_DARK
    val useHomeSidebarBackdrop = isV2Sidebar && !menuOnPicture && currentRoute == Screen.Home.route
    val sidebarHazeState = if (isV2Sidebar && !useHomeSidebarBackdrop) sharedSidebarHazeState ?: fallbackSidebarHazeState else fallbackSidebarHazeState
    // Panel is always laid out at full expanded width; open/close is
    // purely a graphicsLayer transform (slide + alpha in V2) so Compose never
    // re-layouts and haze doesn't re-render blur every frame.
    val sidebarWidth = if (sidebarVisible) openSidebarWidth else collapsedSidebarWidth
    val animationDuration = if (sidebarVisible) 400 else 300
    val animationEasing = if (sidebarVisible) FastOutSlowInEasing else FastOutLinearInEasing

    val sidebarSlideX = NuvioTheme.spacing.none
    val sidebarSurfaceAlpha by animateFloatAsState(
        targetValue = if (isSidebarExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = if (isSidebarExpanded) 280 else 200, easing = animationEasing),
        label = "sidebarSurfaceAlpha"
    )
    val sidebarTransition = updateTransition(
        targetState = isSidebarExpanded,
        label = "sidebarTransition"
    )
    // Labels and icons are always at full size — the panel is rendered
    // complete and the open/close animation is purely graphicsLayer.
    val sidebarLabelAlpha = 1f
    val sidebarExpandProgressState = sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelIn, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = NuvioMotion.tokens.durations.sidebarPanelOut, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarExpandProgress"
    ) { expanded ->
        if (expanded) 1f else 0f
    }
    val sidebarExpandProgress by sidebarExpandProgressState
    // This is a static CompositionLocal: a fresh instance invalidates the whole
    // destination subtree, even when only the focused sidebar item has changed.
    val sidebarPageLayout = remember(isV2Sidebar, showSidebar, sidebarCollapsed, openSidebarWidth, sidebarExpandProgressState) {
        if (isV2Sidebar && showSidebar) com.nuvio.tv.ui.v2.components.SidebarPageLayout(
            if (sidebarCollapsed) 0.dp else 62.dp, openSidebarWidth, { sidebarExpandProgressState.value }
        ) else null
    }

    // derivedStateOf prevents per-frame recomposition — only triggers when the boolean crosses the threshold
    val sidebarBlocksContentKeys by remember { derivedStateOf { sidebarExpandProgress > 0.2f } }
    val sidebarShowExpandedPanel by remember { derivedStateOf { sidebarExpandProgress > 0.01f } }
    val sidebarShowCollapsedPill by remember { derivedStateOf { sidebarExpandProgress < 0.98f } }
    // The small V2 pill uses static glass so browsing does not capture the full
    // content layer for blur. Keep the source through the panel's closing fade.
    val shouldApplySidebarHaze = showSidebar && modernSidebarBlurEnabled &&
        (!isV2Sidebar || (!menuOnPicture && (isSidebarExpanded || sidebarShowExpandedPanel)))
    val mainMenuBackdrop = remember { com.nuvio.tv.ui.v2.components.MainMenuBackdrop() }
    val menuLuminance = remember {
        androidx.compose.animation.core.Animatable(com.nuvio.tv.ui.v2.components.MAIN_MENU_UNKNOWN_PICTURE_LUMINANCE)
    }
    val menuLuminanceCache = remember { HashMap<String, Float>() }
    val menuTransparency = v2Appearance?.glassTransparencyPercent ?: 60
    val menuContext = androidx.compose.ui.platform.LocalContext.current
    val menuDensity = androidx.compose.ui.platform.LocalDensity.current
    val onHome = currentRoute == Screen.Home.route
    LaunchedEffect(menuOnPicture, onHome, isSidebarExpanded) {
        if (!menuOnPicture) return@LaunchedEffect
        if (!onHome) {
            menuLuminance.snapTo(com.nuvio.tv.ui.v2.components.MAIN_MENU_DEFAULT_LUMINANCE)
            return@LaunchedEffect
        }
        if (!isSidebarExpanded) return@LaunchedEffect
        snapshotFlow { mainMenuBackdrop.artworkUrl }.collectLatest { url ->
            if (url == null) {
                menuLuminance.animateTo(com.nuvio.tv.ui.v2.components.MAIN_MENU_DEFAULT_LUMINANCE, tween(220))
                return@collectLatest
            }
            val metrics = menuContext.resources.displayMetrics
            val menuFraction = with(menuDensity) { openSidebarWidth.toPx() } / metrics.widthPixels.coerceAtLeast(1)
            val cacheKey = "$url|$menuFraction|$isRtl"
            val cached = menuLuminanceCache[cacheKey]
            if (cached != null) {
                menuLuminance.snapTo(cached)
                return@collectLatest
            }
            // Until the new picture is measured, err on the readable side.
            menuLuminance.snapTo(maxOf(menuLuminance.value, com.nuvio.tv.ui.v2.components.MAIN_MENU_UNKNOWN_PICTURE_LUMINANCE))
            val sampled = com.nuvio.tv.ui.v2.components.sampleMainMenuBackdrop(
                menuContext, url, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat(), menuFraction, isRtl
            )
            if (sampled != null) {
                if (menuLuminanceCache.size >= 64) menuLuminanceCache.clear()
                menuLuminanceCache[cacheKey] = sampled
            }
            menuLuminance.animateTo(sampled ?: com.nuvio.tv.ui.v2.components.MAIN_MENU_UNKNOWN_PICTURE_LUMINANCE, tween(220))
        }
    }
    val menuShadeAlpha = { com.nuvio.tv.ui.v2.components.mainMenuShadeAlpha(menuLuminance.value, menuTransparency) }
    val profileFocusRequester = remember { FocusRequester() }
    val collapsedPillBlurEnabled = modernSidebarBlurEnabled && !isV2Sidebar

    val sidebarIconScale = 1f
    val sidebarBloomScale = 1f
    val sidebarDeflateOffsetX = NuvioTheme.spacing.none
    val sidebarDeflateOffsetY = NuvioTheme.spacing.none

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isSidebarExpanded, pendingSidebarFocusRequest, showSidebar, selectedDrawerRoute) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isSidebarExpanded) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: drawerItems.firstOrNull()?.route ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    SidebarFocusRecoveryEffect(
        drawerItems = drawerItems,
        selectedDrawerRoute = selectedDrawerRoute,
        drawerItemFocusRequesters = drawerItemFocusRequesters,
        sidebarOwnsFocus = showSidebar && isSidebarExpanded
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if ((pendingDrawerDestination != null || cancellingDrawerDestination) && keyEvent.key == Key.Back) {
                    pendingDrawerDestination = null
                    cancellingDrawerDestination = keyEvent.type != KeyEventType.KeyUp
                    return@onPreviewKeyEvent true
                }
                if (pendingDrawerDestination != null && isBlockedContentKey(keyEvent.key)) {
                    return@onPreviewKeyEvent true
                }
                // Consume all Back key events after long-press until released,
                // preventing the exit-app BackHandler from firing during the hold.
                if (longPressBackHeld.value && keyEvent.key == Key.Back) {
                    if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldApplySidebarHaze && !isV2Sidebar) Modifier.hazeSource(state = sidebarHazeState)
                    else Modifier
                )
                .onPreviewKeyEvent { keyEvent ->
                    // Long-press Back on a root route directly opens the sidebar,
                    // bypassing the "scroll row to start" BackHandler in home content.
                    if (keyEvent.key == Key.Back) {
                        if (
                            keyEvent.type == KeyEventType.KeyDown &&
                            showSidebar &&
                            !isSidebarExpanded &&
                            !sidebarCollapsePending &&
                            currentRoute in rootRoutes &&
                            keyEvent.nativeKeyEvent.isLongPress
                        ) {
                            if (!longPressBackHeld.value) {
                                longPressBackHeld.value = true
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                            }
                            return@onPreviewKeyEvent true
                        }
                        if (longPressBackHeld.value) {
                            if (keyEvent.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (
                        isSidebarExpanded &&
                        !sidebarCollapsePending &&
                        // V2 owns input as soon as opening begins. Letting a rapid
                        // Down reach Home during the animation can detach its focused
                        // card while the sidebar is requesting focus.
                        (isV2Sidebar || sidebarBlocksContentKeys) &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        isBlockedContentKey(keyEvent.key)
                    ) {
                        true
                    } else {
                        false
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (showSidebar && !isSidebarExpanded && keyEvent.type == KeyEventType.KeyDown) {
                        if (!keepFloatingPillExpanded) {
                            when (keyEvent.key) {
                                Key.DirectionDown -> isFloatingPillIconOnly = true
                                Key.DirectionUp -> {
                                    isFloatingPillIconOnly = false
                                    pillExpandRequestCount++
                                }
                                else -> Unit
                            }
                        }
                        val openKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft
                        if (keyEvent.key == openKey) {
                            if (focusManager.moveFocus(if (isRtl) FocusDirection.Right else FocusDirection.Left)) {
                                true
                            } else {
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                                true
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                com.nuvio.tv.ui.v2.components.LocalSidebarBackdropSource provides
                    sidebarHazeState.takeIf { useHomeSidebarBackdrop && shouldApplySidebarHaze },
                com.nuvio.tv.ui.v2.components.LocalSidebarPageLayout provides
                    sidebarPageLayout,
                LocalSidebarExpanded provides isSidebarExpanded,
                LocalContentFocusRequester provides contentFocusRequester,
                com.nuvio.tv.ui.v2.components.LocalMainMenuBackdrop provides mainMenuBackdrop.takeIf { menuOnPicture }
            ) {
                navigationContent(hideBuiltInHeaders)
            }
        }

        if (showSidebar && !detailPageLeaving && (sidebarVisible || sidebarShowExpandedPanel)) {
            val panelShape = RoundedCornerShape(if (isV2Sidebar) 0.dp else sidebarTokens.panelRadius)
            val showExpandedPanel = isSidebarExpanded || sidebarShowExpandedPanel
            if (menuOnPicture && showExpandedPanel) {
                V2MainMenuShade(
                    alpha = { menuShadeAlpha() * sidebarExpandProgress },
                    modifier = Modifier.align(Alignment.TopStart).width(openSidebarWidth)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(openSidebarWidth)
                    .then(if (isV2Sidebar) Modifier else Modifier.padding(start = NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs, top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.md, end = NuvioTheme.spacing.sm))
                    .graphicsLayer {
                        val progress = sidebarExpandProgress
                        alpha = sidebarSurfaceAlpha
                        val s = if (isV2Sidebar) 1f else 0.92f + 0.08f * progress
                        scaleX = s
                        scaleY = s
                        if (isV2Sidebar) translationX = (if (isRtl) 1f else -1f) * size.width * (1f - progress)
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                if (focusedDrawerIndex == sidebarTopBoundaryIndex) {
                                    true
                                } else if (isV2Sidebar && focusedDrawerIndex == 0 && hasSidebarProfileItem) {
                                    // The profile sits across the gap above the centred group.
                                    runCatching { profileFocusRequester.requestFocus() }
                                    true
                                } else {
                                    // Move focus within the sidebar; consume unconditionally
                                    // so focus never escapes into the content behind.
                                    if (focusedDrawerIndex > 0 && focusedDrawerIndex < drawerItems.size) {
                                        drawerItemFocusRequesters[drawerItems[focusedDrawerIndex - 1].route]?.requestFocus()
                                    } else {
                                        focusManager.moveFocus(FocusDirection.Up)
                                    }
                                    true
                                }
                            }

                            Key.DirectionDown -> {
                                if (focusedDrawerIndex == drawerItems.lastIndex) {
                                    // Already at the bottom drawer item — stay put.
                                    true
                                } else if (focusedDrawerIndex == drawerItems.size && hasSidebarProfileItem) {
                                    // Profile → first drawer item: skip moveFocus (the
                                    // Spacer gap causes it to land in content) and
                                    // request the first drawer item directly.
                                    drawerItems.firstOrNull()?.route?.let { route ->
                                        drawerItemFocusRequesters[route]?.requestFocus()
                                    }
                                    true
                                } else {
                                    drawerItems.getOrNull(focusedDrawerIndex + 1)?.route?.let { route ->
                                        drawerItemFocusRequesters[route]?.requestFocus()
                                    }
                                    true
                                }
                            }

                            Key.DirectionRight, Key.DirectionLeft -> {
                                val collapseKey = if (isRtl) Key.DirectionLeft else Key.DirectionRight
                                if (keyEvent.key == collapseKey) {
                                    pendingContentFocusTransfer = false
                                    sidebarCollapsePending = true
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> false
                        }
                    }
            ) {
                if (showExpandedPanel && isV2Sidebar) {
                    V2MainMenuPanel(
                        drawerItems = drawerItems,
                        selectedDrawerRoute = selectedDrawerRoute,
                        focusEnabled = keepSidebarFocusDuringCollapse,
                        sidebarExpandProgress = { sidebarExpandProgress },
                        onPicture = menuOnPicture,
                        drawerItemFocusRequesters = drawerItemFocusRequesters,
                        profileFocusRequester = profileFocusRequester,
                        onDrawerItemFocused = { focusedDrawerIndex = it },
                        onDrawerItemClick = { targetRoute ->
                            keyboardController?.hide()
                            if (currentRoute != targetRoute) {
                                pendingDrawerDestination = targetRoute
                            } else {
                                onNavigate(targetRoute)
                                navigateToDrawerRoute(navController, currentRoute, targetRoute)
                            }
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = currentRoute == targetRoute
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                        showProfileSelector = showProfileSelector,
                        onSwitchProfile = onSwitchProfile
                    )
                } else if (showExpandedPanel) {
                    ModernSidebarBlurPanel(
                        drawerItems = drawerItems,
                        selectedDrawerRoute = selectedDrawerRoute,
                        keepSidebarFocusDuringCollapse = keepSidebarFocusDuringCollapse,
                        sidebarLabelAlpha = sidebarLabelAlpha,
                        sidebarIconScale = sidebarIconScale,
                        sidebarExpandProgress = { sidebarExpandProgress },
                        isSidebarExpanded = isSidebarExpanded,
                        sidebarCollapsePending = sidebarCollapsePending,
                        blurEnabled = modernSidebarBlurEnabled,
                        sidebarHazeState = if (useHomeSidebarBackdrop && sidebarHazeState.areas.isEmpty())
                            sharedSidebarHazeState ?: sidebarHazeState else sidebarHazeState,
                        panelShape = panelShape,
                        drawerItemFocusRequesters = drawerItemFocusRequesters,
                        onDrawerItemFocused = { focusedDrawerIndex = it },
                        onDrawerItemClick = { targetRoute ->
                            keyboardController?.hide()
                            if (isV2Sidebar && currentRoute != targetRoute) {
                                pendingDrawerDestination = targetRoute
                            } else {
                                onNavigate(targetRoute)
                                navigateToDrawerRoute(navController, currentRoute, targetRoute)
                            }
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = currentRoute == targetRoute
                        },
                        activeProfileName = activeProfileName,
                        activeProfileColorHex = activeProfileColorHex,
                        activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                        showProfileSelector = showProfileSelector,
                        onSwitchProfile = onSwitchProfile
                    )
                }
            }

            if (
                !sidebarCollapsed &&
                sidebarShowCollapsedPill &&
                selectedDrawerRoute != Screen.Search.route
            ) {
                if (isV2Sidebar) {
                    if (menuOnPicture) {
                        V2MainMenuShade(
                            alpha = { 0.5f * com.nuvio.tv.ui.v2.components.mainMenuShadeAlpha(
                                com.nuvio.tv.ui.v2.components.MAIN_MENU_DEFAULT_LUMINANCE, menuTransparency
                            ) * (1f - sidebarExpandProgress) },
                            modifier = Modifier.align(Alignment.TopStart).width(70.dp)
                        )
                    }
                    V2MainMenuRail(
                        items = drawerItems,
                        selectedRoute = selectedDrawerRoute,
                        showProfile = showProfileSelector,
                        profileName = activeProfileName,
                        profileColorHex = activeProfileColorHex,
                        profileAvatarImageUrl = activeProfileAvatarImageUrl,
                        modifier = Modifier.align(Alignment.TopStart)
                            .graphicsLayer { alpha = 1f - sidebarExpandProgress },
                        onExpand = {
                            isSidebarExpanded = true
                            sidebarCollapsePending = false
                            pendingSidebarFocusRequest = true
                        }
                    )
                } else CollapsedSidebarPill(
                    label = selectedDrawerItem.label,
                    iconRes = selectedDrawerItem.iconRes,
                    icon = selectedDrawerItem.icon,
                    iconOnly = isFloatingPillIconOnly && !keepFloatingPillExpanded,
                    blurEnabled = collapsedPillBlurEnabled,
                    hazeState = if (collapsedPillBlurEnabled) sidebarHazeState else null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (NuvioTheme.spacing.lg - NuvioTheme.spacing.xxs).roundToPx(),
                                (NuvioTheme.spacing.lg + sidebarDeflateOffsetY).roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val progress = sidebarExpandProgress
                            alpha = 1f - progress
                            val s = 0.9f + (0.1f * (1f - progress))
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    onExpand = {
                        isSidebarExpanded = true
                        sidebarCollapsePending = false
                        pendingSidebarFocusRequest = true
                    }
                )
            }
        }
    }
}

@Composable
private fun CollapsedSidebarPill(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    iconOnly: Boolean,
    blurEnabled: Boolean,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(NuvioRadii.tokens.full)
    val v2Glass = com.nuvio.tv.ui.v2.quality.LocalGlassTokens.current.takeIf {
        com.nuvio.tv.ui.v2.appearance.LocalV2Appearance.current != null
    }
    val colors = NuvioTheme.colors
    val bgElevated = colors.BackgroundElevated
    val bgCard = colors.BackgroundCard
    val borderBase = colors.Border
    val mediaColors = colors.media
    val pillBackgroundBrush = remember(blurEnabled, v2Glass) {
        val alpha = v2Glass?.surfaceAlpha ?: if (blurEnabled) 0.65f else 0.96f
        Brush.verticalGradient(listOf(
            Color(0xFF1C1C1E).copy(alpha = alpha),
            Color(0xFF1C1C1E).copy(alpha = alpha)
        ))
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .clickable(onClick = onExpand)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .padding(horizontal = NuvioTheme.spacing.hairline, vertical = NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .height(NuvioTheme.sizes.player.control)
                .then(
                    if (v2Glass != null) {
                        Modifier.nuvioGlass(GlassRole.NAVIGATION, shape = pillShape,
                            hazeState = hazeState.takeIf { blurEnabled })
                    } else {
                        Modifier.clip(pillShape).then(
                            if (blurEnabled && hazeState != null) {
                                Modifier.hazeEffect(state = hazeState) {
                                    blurRadius = 24.dp
                                    inputScale = HazeInputScale.Fixed(0.66f)
                                }
                            } else Modifier
                        ).background(brush = pillBackgroundBrush, shape = pillShape)
                    }
                )
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(NuvioTheme.sizes.sidebar.leadingVisual),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerItemIcon(
                        iconRes = iconRes,
                        icon = icon,
                        tint = NuvioTheme.colors.text.onOverlay,
                        modifier = Modifier
                            .size(NuvioTheme.sizes.sidebar.leadingVisual - NuvioTheme.spacing.md)
                            .offset(y = (-0.5).dp)
                    )
                }

                AnimatedVisibility(
                    visible = !iconOnly,
                    enter = expandHorizontally(
                            animationSpec = tween(NuvioMotion.tokens.durations.fast, easing = FastOutSlowInEasing),
                            expandFrom = Alignment.Start,
                            clip = true
                        ),
                    exit = shrinkHorizontally(
                            animationSpec = tween(NuvioMotion.tokens.durations.fast, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Start,
                            clip = true
                        )
                ) {
                    Text(
                        text = label,
                        color = NuvioTheme.colors.text.onOverlay,
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                            lineHeight = 30.sp
                        ),
                        modifier = Modifier
                            .padding(start = 9.dp, end = NuvioTheme.spacing.md - 5.dp)
                            .offset(y = (-0.5).dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private val SEARCH_KEY_IGNORED_ROUTES = setOf(
    Screen.ProfileSelection.route, Screen.AuthSignIn.route, Screen.AuthQrSignIn.route,
    Screen.SyncCodeGenerate.route, Screen.SyncCodeClaim.route, Screen.ExperienceModeSelection.route,
    Screen.LayoutSelection.route
)

/** The remote's Search button opens the app's search, except in the player (a Remote buttons job there) and before sign-in. */
internal fun searchButtonOpensSearch(route: String?, playbackUiActive: Boolean): Boolean =
    route != null && !playbackUiActive && route != Screen.Player.route && route !in SEARCH_KEY_IGNORED_ROUTES

internal fun resolveStartExperienceMode(storedMode: ExperienceMode?, hasChosenLayout: Boolean): ExperienceMode? =
    storedMode ?: if (hasChosenLayout) ExperienceMode.ADVANCED else null

/** Launch extras, launcher rows and links open their title once the first-run choice is known, chosen layout or not. */
internal fun launchTargetsReady(storedMode: ExperienceMode?, hasChosenLayout: Boolean): Boolean =
    resolveStartExperienceMode(storedMode, hasChosenLayout) != null

/** Pages with a menu entry; a page without one, such as a hidden Calendar opened from a reminder, goes Back to Home. */
internal fun appRootRoutes(discoverInSidebar: Boolean, calendarInMenu: Boolean): Set<String> = buildSet {
    add(Screen.Home.route)
    add(Screen.Search.route)
    if (com.nuvio.tv.domain.model.ScoutFeature.available) add(Screen.Scout.route)
    add(Screen.Library.route)
    if (calendarInMenu) add(Screen.Calendar.route)
    add(Screen.Settings.route)
    if (discoverInSidebar) add(Screen.Discover.route)
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        if (targetRoute == Screen.Home.route) {
            // Scroll Home to top by clearing saved focus/scroll state on the ViewModel.
            val homeEntry = try {
                navController.getBackStackEntry(Screen.Home.route)
            } catch (_: IllegalArgumentException) {
                // "home" not yet on the back stack (e.g. nav graph not fully initialized).
                return
            }
            val homeViewModel = androidx.lifecycle.ViewModelProvider(homeEntry)[com.nuvio.tv.ui.screens.home.HomeViewModel::class.java]
            homeViewModel.requestScrollToTop()
        }
        if (targetRoute == Screen.Calendar.route) {
            runCatching {
                val calendarEntry = navController.getBackStackEntry(Screen.Calendar.route)
                androidx.lifecycle.ViewModelProvider(calendarEntry)[
                    com.nuvio.tv.ui.screens.calendar.CalendarViewModel::class.java
                ].showToday()
            }
        }
        return
    }
    try {
        navController.navigate(targetRoute) {
            popUpTo(navController.graph.startDestinationId) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } catch (e: IllegalArgumentException) {
        Log.w("NuvioNavigation", "Route not found in nav graph: $targetRoute", e)
    }
}

private fun isBlockedContentKey(key: Key): Boolean {
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}

@Composable
internal fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current,
    brush: Brush? = null
) {
    val iconModifier = if (brush == null) {
        modifier
    } else {
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            }
    }
    val iconTint = if (brush == null) tint else Color.White
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = iconModifier
        )
    }
}

object LocaleCache {
    const val UNSET = "__UNSET__"

    private val tag = kotlinx.coroutines.flow.MutableStateFlow(UNSET)
    val tags: kotlinx.coroutines.flow.StateFlow<String> = tag

    var localeTag: String
        get() = tag.value
        set(value) {
            tag.value = value
        }
}
