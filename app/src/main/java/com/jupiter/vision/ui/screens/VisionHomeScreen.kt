// VisionHomeScreen.kt - 3-band industrial home screen connected to the passive Vision Engine state.
package com.jupiter.vision.ui.screens

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jupiter.vision.R
import com.jupiter.vision.engine.service.VisionEngineService
import com.jupiter.vision.ui.theme.DarkGreyBackground
import com.jupiter.vision.ui.theme.InterFontFamily

/**
 * Data representation for placeholder launcher applications
 */
data class PlaceholderApp(
    val id: String,
    val name: String,
    val iconRes: Int
)

/**
 * Data representation for settings grid status cell
 */
data class SettingStatusItem(
    val id: String,
    val label: String,
    val iconRes: Int,
    val isInteractive: Boolean = false
)

/**
 * VisionHomeScreen - The single screen custom launcher interface for Jupitervision OS (Update 2112.17).
 *
 * Implements a strict, uncompromising industrial aesthetic divided into 3 equal horizontal bands (each 1/3 height):
 * - BAND 1: Full-bleed satellite map snippet with centered location pin and telemetry label.
 * - BAND 2: Centered large time display with tabular figures, bottom-left date, bottom-right weather.
 * - BAND 3: Dimmed wallpaper reveal with 50/50 split between dynamic App List and Settings Grid.
 */
@Composable
fun VisionHomeScreen(
    modifier: Modifier = Modifier,
    viewModel: VisionHomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = VisionHomeViewModelFactory(LocalContext.current.applicationContext as Application)
    ),
    wallpaperBitmap: Bitmap? = null,
    onScreenLock: () -> Unit = { viewModel.onScreenLockClicked() },
    onAppClick: (String) -> Unit = { viewModel.onAppClicked(it) }
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeString by viewModel.timeState.collectAsStateWithLifecycle()
    val dateString by viewModel.dateState.collectAsStateWithLifecycle()

    // 1. Start foreground service when home screen is composed
    LaunchedEffect(Unit) {
        VisionEngineService.start(context)
    }

    // 2. Request permissions on first launch
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Engine handles telemetry passively; UI updates when permissions are granted
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    // Permission status checks for subtle bottom hints
    val hasLocationPermission = remember(uiState) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    val hasUsageAccess = remember(uiState) {
        checkUsageAccessPermission(context)
    }

    // Screen divided into 3 equal horizontal bands (each 1/3 height)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkGreyBackground)
            .testTag("vision_home_screen")
    ) {
        // BAND 1 (Top third): Map & Location
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DarkGreyBackground)
                .testTag("band_1_map")
        ) {
            MapSnippet(
                locationLabel = uiState.locationLabel,
                mapTile = uiState.mapTile,
                modifier = Modifier.fillMaxSize()
            )
        }

        // BAND 2 (Middle third): Time, Date, Weather
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(DarkGreyBackground)
                .testTag("band_2_time")
        ) {
            TimeDisplay(
                time = timeString,
                date = dateString,
                weatherText = uiState.weatherText,
                weatherIconRes = uiState.weatherIconRes,
                modifier = Modifier.fillMaxSize()
            )
        }

        // BAND 3 (Bottom third): Dimmed Wallpaper Reveal with 50/50 two-column split
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("band_3_bottom")
        ) {
            // Wallpaper reveal background (fallback to DarkGreyBackground if null)
            val activeWallpaper = uiState.wallpaper ?: wallpaperBitmap?.asImageBitmap()
            if (activeWallpaper != null) {
                Image(
                    bitmap = activeWallpaper,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // 50% black overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.50f))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DarkGreyBackground)
                )
            }

            // Split into two columns: 50% App List, 50% Settings Grid
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    // LEFT COLUMN: App List (50%)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("app_list_column")
                    ) {
                        AppList(
                            apps = uiState.appList,
                            onAppClicked = onAppClick,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Vertical Divider (1dp, white at alpha 0.15, cut 18dp from top and bottom)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = 18.dp)
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.15f))
                            .testTag("column_divider")
                    )

                    // RIGHT COLUMN: Settings Grid (50%)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("settings_grid_column")
                    ) {
                        SettingsGrid(
                            soundMode = uiState.soundMode,
                            networkStatus = uiState.networkStatus,
                            batteryPercent = uiState.batteryPercent,
                            storagePercent = uiState.storagePercent,
                            onScreenLockClicked = onScreenLock,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Subtle permission warning text if permissions are missing
                if (!hasLocationPermission) {
                    Text(
                        text = "ENABLE LOCATION FOR BETTER DETECTION.",
                        fontFamily = InterFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                            }
                            .padding(bottom = 6.dp)
                    )
                } else if (!hasUsageAccess) {
                    Text(
                        text = "ENABLE USAGE ACCESS FOR BETTER DETECTION.",
                        fontFamily = InterFontFamily,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.onUsageAccessClicked()
                            }
                            .padding(bottom = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * BAND 1: MapSnippet
 * If mapTile is null: show dark placeholder rect with centered location pin.
 * If mapTile is present: show tile as background, centered pin, fade effect at bottom 20-35%.
 * Location label text rendered at top-left with alpha 0.75.
 */
@Composable
fun MapSnippet(
    locationLabel: String,
    mapTile: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.testTag("map_snippet"),
        contentAlignment = Alignment.Center
    ) {
        if (mapTile != null) {
            Image(
                bitmap = mapTile,
                contentDescription = "Current Location Map Snippet",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Top edge fade: blends smoothly from #1A1A1A to transparent
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.22f)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                DarkGreyBackground.copy(alpha = 0.85f),
                                DarkGreyBackground.copy(alpha = 0.40f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Bottom blend into Band 2: smooth multi-stop fade scaling down to #1A1A1A
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.35f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                DarkGreyBackground.copy(alpha = 0.25f),
                                DarkGreyBackground.copy(alpha = 0.70f),
                                DarkGreyBackground
                            )
                        )
                    )
            )
        } else {
            // Solid dark placeholder rect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkGreyBackground)
            )
        }

        // Location pin icon centered on the map snippet
        Icon(
            painter = painterResource(id = R.drawable.ic_location_pin),
            contentDescription = "Current Location Pin",
            tint = Color.White,
            modifier = Modifier
                .size(28.dp)
                .align(Alignment.Center)
                .testTag("location_pin_icon")
        )

        // Top-left text overlay: "CURRENT LOCATION: {LOCATION LABEL}"
        val formattedLabel = if (locationLabel.startsWith("CURRENT LOCATION", ignoreCase = true)) {
            locationLabel.uppercase()
        } else {
            "CURRENT LOCATION: ${locationLabel.uppercase()}"
        }

        Text(
            text = formattedLabel,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 1.3.sp,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp)
                .testTag("current_location_text")
        )
    }
}

/**
 * BAND 2: TimeDisplay
 * Time: large, tabular figures, white, 72sp, centered.
 * Date: bottom-left, 12sp, muted, uppercase.
 * Weather: bottom-right, icon + text, 12sp, muted, uppercase.
 */
@Composable
fun TimeDisplay(
    time: String,
    date: String,
    weatherText: String,
    weatherIconRes: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
    ) {
        val availableWidth = maxWidth
        val adaptiveFontSize = (availableWidth.value / 6.4f).coerceIn(48f, 72f).sp

        // Large time display in Inter with tabular figures centered
        Text(
            text = time,
            style = TextStyle(
                fontFamily = InterFontFamily,
                fontWeight = FontWeight.Light,
                fontSize = adaptiveFontSize,
                fontFeatureSettings = "tnum",
                letterSpacing = 1.5.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("time_display")
        )

        // Bottom-left corner: date text (e.g. "MON, 12 JUN")
        Text(
            text = date,
            fontFamily = InterFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White.copy(alpha = 0.5f),
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp)
                .testTag("date_display")
        )

        // Bottom-right corner: weather icon + text
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .testTag("weather_display")
        ) {
            Icon(
                painter = painterResource(id = weatherIconRes),
                contentDescription = "Weather Icon",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = weatherText.uppercase(),
                fontFamily = InterFontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 0.6.sp
            )
        }
    }
}

/**
 * BAND 3 LEFT COLUMN: AppList
 * 5 rows stacked vertically, each row: icon (40dp) + label (14sp) + clickable.
 * Row height 48dp. If apps.size < 5, pad with empty rows so layout stays stable.
 */
@Composable
fun AppList(
    apps: List<AppItem>,
    onAppClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (apps.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No apps",
                fontFamily = InterFontFamily,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        return
    }

    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Take up to 5 apps
        val displayedApps = apps.take(5)
        for (app in displayedApps) {
            AppRow(
                app = app,
                onClick = { onAppClicked(app.packageName) },
                modifier = Modifier.weight(1f)
            )
        }

        // Pad with empty rows if fewer than 5 apps to maintain layout stability
        val emptySlots = 5 - displayedApps.size
        for (i in 0 until emptySlots) {
            Spacer(modifier = Modifier.weight(1f).height(48.dp))
        }
    }
}

@Composable
private fun AppRow(
    app: AppItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(app.icon) {
        drawableToBitmap(app.icon).asImageBitmap()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.White),
                onClick = onClick
            )
            .padding(horizontal = 14.dp)
            .testTag("app_row_${app.packageName}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "${app.label} icon",
            modifier = Modifier
                .size(40.dp)
                .testTag("app_icon_${app.packageName}")
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = app.label,
            fontFamily = InterFontFamily,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            color = Color.White,
            letterSpacing = 0.3.sp,
            maxLines = 1
        )
    }
}

/**
 * BAND 3 RIGHT COLUMN: SettingsGrid
 * 2x3 invisible grid:
 * Row 1: Sound Mode (icon + text), Internet (icon + text)
 * Row 2: Battery (icon + text), Storage (icon + text)
 * Row 3: Screen Lock (icon + text, brighter alpha 0.9, clickable), Reserved (empty)
 */
@Composable
fun SettingsGrid(
    soundMode: String,
    networkStatus: String,
    batteryPercent: Int,
    storagePercent: Int,
    onScreenLockClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val soundIcon = when (soundMode.uppercase()) {
        "VIBRATE" -> R.drawable.ic_setting_vibrate
        "SILENT" -> R.drawable.ic_setting_silent
        else -> R.drawable.ic_setting_sound
    }

    val networkIcon = when (networkStatus.uppercase()) {
        "CELLULAR" -> R.drawable.ic_setting_cellular
        "OFFLINE" -> R.drawable.ic_setting_offline
        else -> R.drawable.ic_setting_wifi
    }

    Column(
        modifier = modifier
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag("settings_grid"),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Row 1: Sound Mode, Internet
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SettingsGridCell(
                label = soundMode.uppercase(),
                iconRes = soundIcon,
                modifier = Modifier.weight(1f)
            )
            SettingsGridCell(
                label = networkStatus.uppercase(),
                iconRes = networkIcon,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Battery, Storage
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SettingsGridCell(
                label = "$batteryPercent%",
                iconRes = R.drawable.ic_setting_battery,
                modifier = Modifier.weight(1f)
            )
            SettingsGridCell(
                label = "$storagePercent%",
                iconRes = R.drawable.ic_setting_storage,
                modifier = Modifier.weight(1f)
            )
        }

        // Row 3: Screen Lock, Reserved (empty)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SettingsGridCell(
                label = "LOCK",
                iconRes = R.drawable.ic_setting_lock,
                isInteractive = true,
                alpha = 0.90f,
                onClick = onScreenLockClicked,
                modifier = Modifier.weight(1f)
            )
            // Reserved cell (empty)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun SettingsGridCell(
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    isInteractive: Boolean = false,
    alpha: Float = 0.70f,
    onClick: () -> Unit = {}
) {
    val clickableModifier = if (isInteractive) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = Color.White),
            onClick = onClick
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .then(clickableModifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
            color = Color.White.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

private fun checkUsageAccessPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}
