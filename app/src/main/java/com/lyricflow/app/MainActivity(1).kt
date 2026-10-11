package com.lyricflow.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val Bg = Color(0xFF050A12)
private val SurfaceDark = Color(0xFF0D1520)
private val SurfaceRaised = Color(0xFF111C29)
private val SurfaceSoft = Color(0xFF162232)
private val TextPrimary = Color(0xFFF7F8FC)
private val TextSecondary = Color(0xFFA7B0C0)
private val TextMuted = Color(0xFF687487)
private val Purple = Color(0xFF9B5CFF)
private val Pink = Color(0xFFFF4FB3)
private val Blue = Color(0xFF4C8DFF)
private val Green = Color(0xFF27D889)

private enum class AppPage { INTRO, HOME, SETTINGS, ABOUT, NOW_PLAYING, APPEARANCE, PERMISSIONS }

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("lyricflow_preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.rgb(5, 10, 18)
        window.navigationBarColor = AndroidColor.rgb(5, 10, 18)
        window.decorView.systemUiVisibility = 0
        setContent { LyricFlowApp() }
    }

    private fun openNotificationAccess() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    private fun openOverlayAccess() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun overlayGranted(): Boolean = Settings.canDrawOverlays(this)

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabled.split(":").any { component ->
            runCatching { ComponentName.unflattenFromString(component)?.packageName == packageName }
                .getOrDefault(false)
        }
    }

    private fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("overlay_enabled", enabled).apply()
        if (enabled && overlayGranted()) {
            ContextCompat.startForegroundService(this, Intent(this, LyricsOverlayService::class.java))
        } else if (!enabled) {
            stopService(Intent(this, LyricsOverlayService::class.java))
        }
    }

    @Composable
    private fun LyricFlowApp() {
        var page by remember {
            mutableStateOf(if (prefs.getBoolean("intro_seen", false)) AppPage.HOME else AppPage.INTRO)
        }
        var refreshTick by remember { mutableStateOf(0) }
        var overlayEnabled by remember { mutableStateOf(prefs.getBoolean("overlay_enabled", false)) }

        LaunchedEffect(Unit) {
            while (true) {
                delay(600)
                refreshTick++
                overlayEnabled = prefs.getBoolean("overlay_enabled", false)
            }
        }

        val track = PlaybackStore.track
        val lyrics = LyricsStore.current
        val currentPage = page

        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                background = Bg,
                surface = SurfaceDark,
                surfaceContainer = SurfaceRaised,
                primary = Purple,
                secondary = Pink,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
                onSurfaceVariant = TextSecondary
            )
        ) {
            Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        if (currentPage == AppPage.HOME || currentPage == AppPage.SETTINGS || currentPage == AppPage.ABOUT) {
                            BottomNav(page = currentPage, onPage = { page = it })
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AnimatedContent(
                            targetState = currentPage,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "page_transition"
                        ) { target ->
                            when (target) {
                                AppPage.INTRO -> IntroScreen(
                                    onStart = {
                                        prefs.edit().putBoolean("intro_seen", true).apply()
                                        page = AppPage.HOME
                                    }
                                )
                                AppPage.HOME -> HomeScreen(
                                    track = track,
                                    lyrics = lyrics,
                                    overlayEnabled = overlayEnabled,
                                    notificationGranted = notificationAccessGranted(),
                                    overlayGranted = overlayGranted(),
                                    onOverlayToggle = {
                                        val desired = !overlayEnabled
                                        if (desired && !overlayGranted()) {
                                            openOverlayAccess()
                                        } else {
                                            setOverlayEnabled(desired)
                                            overlayEnabled = desired
                                        }
                                    },
                                    onOpenSettings = { page = AppPage.SETTINGS },
                                    onOpenAppearance = { page = AppPage.APPEARANCE },
                                    onOpenPermissions = { page = AppPage.PERMISSIONS },
                                    onOpenNowPlaying = { page = AppPage.NOW_PLAYING }
                                )
                                AppPage.SETTINGS -> SettingsScreen(
                                    overlayEnabled = overlayEnabled,
                                    onOverlayToggle = {
                                        val desired = !overlayEnabled
                                        if (desired && !overlayGranted()) openOverlayAccess()
                                        else {
                                            setOverlayEnabled(desired)
                                            overlayEnabled = desired
                                        }
                                    },
                                    onAppearance = { page = AppPage.APPEARANCE },
                                    onPermissions = { page = AppPage.PERMISSIONS }
                                )
                                AppPage.ABOUT -> AboutScreen()
                                AppPage.NOW_PLAYING -> NowPlayingScreen(track, lyrics, onBack = { page = AppPage.HOME })
                                AppPage.APPEARANCE -> AppearanceScreen(prefs, onBack = { page = AppPage.HOME })
                                AppPage.PERMISSIONS -> PermissionsScreen(
                                    notificationGranted = notificationAccessGranted(),
                                    overlayGranted = overlayGranted(),
                                    onNotification = { openNotificationAccess() },
                                    onOverlay = { openOverlayAccess() },
                                    onBack = { page = AppPage.HOME }
                                )
                            }
                        }
                    }
                }
            }
        }
        @Suppress("UNUSED_VARIABLE")
        val ignored = refreshTick
    }

    @Composable
    private fun BottomNav(page: AppPage, onPage: (AppPage) -> Unit) {
        Surface(color = Color(0xF5070D15), tonalElevation = 0.dp) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem("Home", Icons.Default.Home, page == AppPage.HOME) { onPage(AppPage.HOME) }
                NavItem("Settings", Icons.Default.Settings, page == AppPage.SETTINGS) { onPage(AppPage.SETTINGS) }
                NavItem("About", Icons.Default.Info, page == AppPage.ABOUT) { onPage(AppPage.ABOUT) }
            }
        }
    }

    @Composable
    private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = if (selected) Purple else TextMuted, modifier = Modifier.size(22.dp))
            Text(label, color = if (selected) TextPrimary else TextMuted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }

    @Composable
    private fun IntroScreen(onStart: () -> Unit) {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF10152A), Color(0xFF170A1A), Bg)))
                .padding(24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))
                GradientLogo(112.dp)
                Spacer(Modifier.height(22.dp))
                Text("LeRisk", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, style = gradientTextStyle())
                Text("Live lyrics, wherever you listen.", color = Color(0xFFFFA9E3), fontSize = 15.sp)
                Text("by !KO", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(26.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IntroFeature(Icons.Default.DisplaySettings, "Works on\nany app")
                    IntroFeature(Icons.Default.MusicNote, "Real-time\nsynced lyrics")
                    IntroFeature(Icons.Default.FavoriteBorder, "Stay in\nthe moment")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Blue, Purple, Pink)), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Get Started", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Clean • Minimal • No Distraction", color = TextMuted, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun IntroFeature(icon: ImageVector, text: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
            IconBadge(icon, TextSecondary)
            Spacer(Modifier.height(7.dp))
            Text(text, color = TextSecondary, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun HomeScreen(
        track: TrackInfo,
        lyrics: LyricsResult?,
        overlayEnabled: Boolean,
        notificationGranted: Boolean,
        overlayGranted: Boolean,
        onOverlayToggle: () -> Unit,
        onOpenSettings: () -> Unit,
        onOpenAppearance: () -> Unit,
        onOpenPermissions: () -> Unit,
        onOpenNowPlaying: () -> Unit
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LeRisk", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, style = gradientTextStyle())
                        Text("Live lyrics, wherever you listen.", color = TextSecondary, fontSize = 14.sp)
                        Text("by !KO", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextPrimary)
                    }
                }
            }
            item {
                NowPlayingCard(track, lyrics, onOpenNowPlaying)
            }
            item {
                GlassSwitchCard(
                    icon = Icons.Default.DisplaySettings,
                    title = "Overlay",
                    subtitle = "Show floating lyrics on any app",
                    checked = overlayEnabled,
                    onCheckedChange = { onOverlayToggle() }
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniActionCard(Icons.Default.Tune, "Overlay\nSettings") { onOpenSettings() }
                    MiniActionCard(Icons.Default.Palette, "Appearance") { onOpenAppearance() }
                    MiniActionCard(Icons.Default.Security, "Permissions") { onOpenPermissions() }
                }
            }
            item { SectionTitle("Live Status") }
            item {
                StatusCard(
                    rows = listOf(
                        StatusRow(Icons.Default.PlayCircle, "Music Detected", if (track.isPlaying) "Playing" else "Waiting", track.isPlaying),
                        StatusRow(Icons.Default.MusicNote, "Lyrics Found", if (lyrics?.synced?.isNotEmpty() == true) "Synced lyrics" else if (LyricsStore.loading) "Searching…" else "Not found", lyrics?.synced?.isNotEmpty() == true),
                        StatusRow(Icons.Default.DisplaySettings, "Overlay", if (overlayEnabled) "Running" else "Off", overlayEnabled)
                    )
                )
            }
            item {
                PermissionSummary(notificationGranted, overlayGranted, onOpenPermissions)
            }
        }
    }

    @Composable
    private fun NowPlayingCard(track: TrackInfo, lyrics: LyricsResult?, onClick: () -> Unit) {
        val playing = track.isPlaying
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .07f))
        ) {
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF18243A), Color(0xFF111725), Color(0xFF26152D)))).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientLogo(size = 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NOW PLAYING", color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(4.dp))
                        val hasTrack = track.title.isNotBlank() &&
                            !track.title.equals("Unknown title", ignoreCase = true) &&
                            !track.title.equals("Nothing playing", ignoreCase = true)
                        Text(
                            if (hasTrack) track.title else "Nothing playing right now",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (hasTrack) track.artist.ifBlank { "Artist unavailable" } else "Start music in any supported app",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        StatusPill(
                            text = when {
                                lyrics?.synced?.isNotEmpty() == true -> "Lyrics Active"
                                LyricsStore.loading -> "Finding lyrics…"
                                playing -> "Listening"
                                else -> "Waiting"
                            },
                            active = lyrics?.synced?.isNotEmpty() == true
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (playing) "●" else "○", color = if (playing) Green else TextMuted, fontSize = 22.sp)
                        Text("›", color = TextSecondary, fontSize = 30.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun GlassSwitchCard(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .06f))
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, Purple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(subtitle, fontSize = 12.sp, color = TextMuted)
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        }
    }

    @Composable
    private fun RowScope.MiniActionCard(icon: ImageVector, title: String, onClick: () -> Unit) {
        Card(
            Modifier.weight(1f).height(78.dp).clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .05f))
        ) {
            Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text(title, color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }

    private data class StatusRow(val icon: ImageVector, val title: String, val value: String, val active: Boolean)

    @Composable
    private fun StatusCard(rows: List<StatusRow>) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 5.dp)) {
                rows.forEachIndexed { index, row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(row.icon, if (row.active) Green else TextMuted, small = true)
                        Spacer(Modifier.width(12.dp))
                        Text(row.title, Modifier.weight(1f), color = TextPrimary, fontSize = 13.sp)
                        Text(row.value, color = if (row.active) Green else TextMuted, fontSize = 11.sp)
                    }
                    if (index != rows.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .05f))
                }
            }
        }
    }

    @Composable
    private fun PermissionSummary(notificationGranted: Boolean, overlayGranted: Boolean, onClick: () -> Unit) {
        val good = notificationGranted && overlayGranted
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (good) Color(0xFF0B211A) else Color(0xFF211A0B)).clickable(onClick = onClick).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (good) Icons.Default.CheckCircle else Icons.Default.Security, null, tint = if (good) Green else Color(0xFFFFC857), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (good) "LeRisk is ready" else "Finish setup", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(if (good) "All required access is available" else "Grant media detection and overlay access", color = TextMuted, fontSize = 11.sp)
            }
            Icon(Icons.Default.ArrowForward, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    private fun SettingsScreen(overlayEnabled: Boolean, onOverlayToggle: () -> Unit, onAppearance: () -> Unit, onPermissions: () -> Unit) {
        LazyColumn(contentPadding = PaddingValues(18.dp, 22.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageTitle("Settings", "Control how LeRisk behaves.") }
            item { SectionTitle("Overlay Control") }
            item { GlassSwitchCard(Icons.Default.DisplaySettings, "Enable Overlay", "Show floating lyrics on any app", overlayEnabled, { onOverlayToggle() }) }
            item { SettingRow(Icons.Default.Tune, "Overlay Position", "Drag to move • position is remembered") { onAppearance() } }
            item { SectionTitle("Appearance") }
            item { SettingRow(Icons.Default.Palette, "Appearance", "Modern • compact • distraction-free") { onAppearance() } }
            item { SettingRow(Icons.Default.ViewCompact, "Visible Lines", "3 lines • previous / current / next") { onAppearance() } }
            item { SectionTitle("Permissions") }
            item { SettingRow(Icons.Default.Security, "Media Detection & Overlay", "Manage required Android permissions") { onPermissions() } }
            item { SectionTitle("Behavior") }
            item { GlassSwitchCard(Icons.Default.DarkMode, "Dark Interface", "Designed for night listening", true) {} }
        }
    }

    @Composable
    private fun AppearanceScreen(prefs: android.content.SharedPreferences, onBack: () -> Unit) {
        var fontSize by remember { mutableStateOf(prefs.getFloat("font_size", 18f)) }
        var currentSize by remember { mutableStateOf(prefs.getFloat("current_size", 22f)) }
        var opacity by remember { mutableStateOf(prefs.getFloat("opacity", 0.84f)) }
        var radius by remember { mutableStateOf(prefs.getFloat("radius", 16f)) }
        var alignment by remember { mutableStateOf(prefs.getInt("alignment", 1)) }

        fun resetAppearance() {
            fontSize = 18f
            currentSize = 22f
            opacity = 0.84f
            radius = 16f
            alignment = 1
            prefs.edit()
                .putFloat("font_size", fontSize)
                .putFloat("current_size", currentSize)
                .putFloat("opacity", opacity)
                .putFloat("radius", radius)
                .putInt("alignment", alignment)
                .apply()
        }

        Column(Modifier.fillMaxSize().padding(18.dp)) {
            BackHeader("Appearance", onBack)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                item {
                    PreviewCard(fontSize, currentSize, opacity, radius, alignment)
                }
                item { SliderSetting("Font Size", fontSize, 12f, 24f) { fontSize = it; prefs.edit().putFloat("font_size", it).apply() } }
                item { SliderSetting("Current Line Size", currentSize, 16f, 30f) { currentSize = it; prefs.edit().putFloat("current_size", it).apply() } }
                item { SliderSetting("Background Opacity", opacity, .35f, .95f, display = "${(opacity * 100).toInt()}%") { opacity = it; prefs.edit().putFloat("opacity", it).apply() } }
                item { SliderSetting("Corner Radius", radius, 8f, 28f, display = "${radius.toInt()} dp") { radius = it; prefs.edit().putFloat("radius", it).apply() } }
                item {
                    Text("Text Alignment", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Left", "Center", "Right").forEachIndexed { index, label ->
                            OutlinedButton(onClick = { alignment = index; prefs.edit().putInt("alignment", index).apply() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (alignment == index) TextPrimary else TextSecondary), border = androidx.compose.foundation.BorderStroke(1.dp, if (alignment == index) Purple else Color.White.copy(alpha = .12f))) { Text(label) }
                        }
                    }
                }
                item { SettingRow(Icons.Default.MusicNote, "Lyrics Style", "Modern • bold current line") {} }
                item { SettingRow(Icons.Default.ViewCompact, "Visible Lines", "3 lines • previous / current / next") {} }
                item {
                    OutlinedButton(
                        onClick = { resetAppearance() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .18f))
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reset appearance to defaults", fontWeight = FontWeight.SemiBold)
                    }
                }
                item {
                    Text(
                        "Changes apply to the floating lyrics while the overlay is running.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun PreviewCard(fontSize: Float, currentSize: Float, opacity: Float, radius: Float, alignment: Int) {
        val textAlign = when (alignment) { 0 -> TextAlign.Left; 2 -> TextAlign.Right; else -> TextAlign.Center }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(radius.dp), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = .7f))) {
            Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF16243B), Color(0xFF16101E)))).padding(18.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♪  LeRisk", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Tu meri ban jaa", color = TextSecondary, fontSize = fontSize.sp, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                    Text("Main tera ban jaunga", color = TextPrimary, fontSize = currentSize.sp, fontWeight = FontWeight.Bold, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                    Text("Tu meri ban jaa", color = TextSecondary, fontSize = fontSize.sp, textAlign = textAlign, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("Opacity ${(opacity * 100).toInt()}%", color = TextMuted, fontSize = 9.sp)
                }
            }
        }
    }

    @Composable
    private fun SliderSetting(label: String, value: Float, min: Float, max: Float, display: String = value.toInt().toString(), onValueChange: (Float) -> Unit) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(display, color = TextSecondary, fontSize = 12.sp)
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = min..max)
        }
    }

    @Composable
    private fun PermissionsScreen(notificationGranted: Boolean, overlayGranted: Boolean, onNotification: () -> Unit, onOverlay: () -> Unit, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            BackHeader("Permissions", onBack)
            Text("LeRisk needs a few permissions to work properly.", color = TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            PermissionCard(Icons.Default.Notifications, "Notification Access", "Detect the currently playing media", notificationGranted, onNotification)
            Spacer(Modifier.height(10.dp))
            PermissionCard(Icons.Default.DisplaySettings, "Display over other apps", "Show the floating lyrics overlay", overlayGranted, onOverlay)
            Spacer(Modifier.height(10.dp))
            PermissionCard(Icons.Default.BatteryChargingFull, "Battery Optimization", "Recommended for long listening sessions", true) {}
            Spacer(Modifier.weight(1f))
            Text("LeRisk never controls your music. It only reads media-session information and displays lyrics.", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
    }

    @Composable
    private fun PermissionCard(icon: ImageVector, title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, if (granted) Green else Purple)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(subtitle, color = TextMuted, fontSize = 11.sp)
                }
                StatusPill(if (granted) "Granted" else "Grant", granted)
                if (!granted) Icon(Icons.Default.KeyboardArrowRight, null, tint = TextMuted)
            }
        }
    }

    @Composable
    private fun NowPlayingScreen(track: TrackInfo, lyrics: LyricsResult?, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            BackHeader("Now Playing", onBack)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF27364C), Color(0xFF371C38), Color(0xFF111722))))) {
                Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    GradientLogo(104.dp)
                    Spacer(Modifier.height(16.dp))
                    val hasTrack = track.title.isNotBlank() &&
                        !track.title.equals("Unknown title", ignoreCase = true) &&
                        !track.title.equals("Nothing playing", ignoreCase = true)
                    Text(
                        if (hasTrack) track.title else "Nothing playing right now",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (hasTrack) track.artist.ifBlank { "Artist unavailable" } else "Start music in any supported app",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    StatusPill(if (lyrics?.synced?.isNotEmpty() == true) "Lyrics Active" else "Lyrics unavailable", lyrics?.synced?.isNotEmpty() == true)
                }
            }
            Spacer(Modifier.height(18.dp))
            SectionTitle("Lyrics Source")
            SettingRow(Icons.Default.Wifi, "LRCLIB", if (lyrics?.synced?.isNotEmpty() == true) "Synced" else "Waiting") {}
            SettingRow(Icons.Default.MusicNote, "Line Count", "${lyrics?.synced?.size ?: 0} lines") {}
            SettingRow(Icons.Default.MusicNote, "Source App", track.packageName.ifBlank { "—" }) {}
        }
    }

    @Composable
    private fun AboutScreen() {
        LazyColumn(contentPadding = PaddingValues(18.dp, 24.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    GradientLogo(86.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("LeRisk", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, style = gradientTextStyle())
                    Text("by !KO", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Version 1.0.0", color = TextMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Your lyrics, everywhere.", color = TextSecondary, fontSize = 14.sp)
                }
            }
            item { SectionTitle("About LeRisk") }
            listOf(
                Triple(Icons.Default.Info, "How it works", "Detects active media and shows synchronized lyrics."),
                Triple(Icons.Default.PlayCircle, "Supported Apps", "Works with apps that expose Android media-session metadata."),
                Triple(Icons.Default.Code, "Open Source Libraries", "Built with Android, Kotlin and Jetpack Compose."),
                Triple(Icons.Default.Security, "Privacy", "LeRisk does not control playback or upload your media."),
                Triple(Icons.Default.FavoriteBorder, "Made for music moments", "Clean lyrics without taking over your screen.")
            ).forEach { (icon, title, subtitle) ->
                item { SettingRow(icon, title, subtitle) {} }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Text("“Music hits different when you understand the words.”  ♥", color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(20.dp))
                }
            }
        }
    }

    @Composable
    private fun PageTitle(title: String, subtitle: String) {
        Column {
            Text(title, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = TextSecondary, fontSize = 13.sp)
        }
    }

    @Composable
    private fun BackHeader(title: String, onBack: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
            Text(title, Modifier.weight(1f), color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.width(48.dp))
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, TextSecondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.KeyboardArrowRight, null, tint = TextMuted)
            }
        }
    }

    @Composable
    private fun IconBadge(icon: ImageVector, tint: Color, small: Boolean = false) {
        Box(Modifier.size(if (small) 34.dp else 40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .11f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(if (small) 18.dp else 21.dp))
        }
    }

    @Composable
    private fun StatusPill(text: String, active: Boolean) {
        Row(Modifier.clip(RoundedCornerShape(50)).background(if (active) Green.copy(alpha = .13f) else Color.White.copy(alpha = .06f)).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (active) Green else TextMuted))
            Spacer(Modifier.width(6.dp))
            Text(text, color = if (active) Green else TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun GradientLogo(size: androidx.compose.ui.unit.Dp) {
        Box(Modifier.size(size).shadow(8.dp, RoundedCornerShape(size * .25f)).clip(RoundedCornerShape(size * .25f)).background(Brush.linearGradient(listOf(Purple, Blue, Pink))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(size * .45f))
        }
    }

    private fun gradientTextStyle() = androidx.compose.ui.text.TextStyle(
        brush = Brush.horizontalGradient(listOf(Color(0xFFFF9DE6), Color(0xFF9C6BFF), Color(0xFF57A8FF))),
        fontWeight = FontWeight.ExtraBold
    )
}
