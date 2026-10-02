/**
 * TESTING CHECKLIST:
 * - Time ticks every second without full-screen recomposition.
 * - Location label updates when venue changes.
 * - App list updates when phase changes.
 * - Settings statuses update on system events.
 * - Screen lock button locks the device.
 * - Wallpaper reveal matches the tile grid's wallpaper.
 */
package com.jupiter.vision.ui.screens

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jupiter.vision.R
import com.jupiter.vision.data.wallpaper.WallpaperProvider
import com.jupiter.vision.data.weather.WeatherRepository
import com.jupiter.vision.engine.VisionEngineFacade
import com.jupiter.vision.receiver.VisionDeviceAdminReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

data class AppItem(
    val icon: Drawable,
    val label: String,
    val packageName: String
)

data class VisionHomeUiState(
    val locationLabel: String = "UNKNOWN",
    val mapTile: ImageBitmap? = null,
    val currentTime: String = "12:00:00",
    val currentDate: String = "MON, 12 JUN",
    val weatherText: String = "--° --",
    val weatherIconRes: Int = R.drawable.ic_weather_clear,
    val appList: List<AppItem> = emptyList(),
    val soundMode: String = "RING",
    val networkStatus: String = "WIFI",
    val batteryPercent: Int = 100,
    val storagePercent: Int = 50,
    val wallpaper: ImageBitmap? = null
)

@HiltViewModel
class VisionHomeViewModel @Inject constructor(
    private val engineFacade: VisionEngineFacade,
    private val weatherRepository: WeatherRepository,
    private val wallpaperProvider: WallpaperProvider,
    private val application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(VisionHomeUiState())
    val uiState: StateFlow<VisionHomeUiState> = _uiState.asStateFlow()

    // Independent lightweight state for second ticks to avoid full recomposition
    private val _timeState = MutableStateFlow(formatCurrentTime())
    val timeState: StateFlow<String> = _timeState.asStateFlow()

    private val _dateState = MutableStateFlow(formatCurrentDate())
    val dateState: StateFlow<String> = _dateState.asStateFlow()

    private val okHttpClient = OkHttpClient()

    // In-memory cache for map tiles with 5-minute TTL
    private data class CachedTile(val bitmap: ImageBitmap, val timestamp: Long)
    private val mapTileCache = LruCache<String, CachedTile>(20)
    private val tileTtlMillis = 5 * 60 * 1000L

    private var lastAppListRefreshTime = 0L

    init {
        // 1. Observe venue changes from engine facade
        viewModelScope.launch {
            engineFacade.currentVenue.collect { venue ->
                val label = engineFacade.getCurrentVenueLabel()
                _uiState.update { it.copy(locationLabel = label) }

                if (venue != null) {
                    val tile = fetchMapTile(venue.latitude, venue.longitude)
                    _uiState.update { it.copy(mapTile = tile) }
                    // Trigger weather update for current venue location
                    refreshWeather(venue.latitude, venue.longitude)
                } else {
                    _uiState.update { it.copy(mapTile = null) }
                }
            }
        }

        // 2. Observe phase changes to refresh app list with debounce (min 2s)
        viewModelScope.launch {
            engineFacade.currentPhase.collect {
                val now = System.currentTimeMillis()
                if (now - lastAppListRefreshTime >= 2000L) {
                    lastAppListRefreshTime = now
                    refreshAppList()
                }
            }
        }

        // 3. Separate coroutine ticking every 1 second for tabular figures time & date
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val timeStr = formatCurrentTime()
                val dateStr = formatCurrentDate()
                _timeState.value = timeStr
                _dateState.value = dateStr
                _uiState.update { it.copy(currentTime = timeStr, currentDate = dateStr) }
                delay(1000L)
            }
        }

        // 4. Separate coroutine ticking every 30 minutes for weather refresh
        viewModelScope.launch {
            while (isActive) {
                val venue = engineFacade.currentVenue.value
                val lat = venue?.latitude ?: 37.7749
                val lng = venue?.longitude ?: -122.4194
                refreshWeather(lat, lng)
                delay(30 * 60 * 1000L)
            }
        }

        // 5. Observe wallpaper changes from WallpaperProvider
        viewModelScope.launch {
            wallpaperProvider.wallpaper.collect { wp ->
                _uiState.update { it.copy(wallpaper = wp) }
            }
        }

        // 6. BroadcastReceivers for ringer mode and battery
        registerSystemReceivers()

        // 7. ConnectivityManager network callback
        registerNetworkCallback()

        // 8. One-time fetch of storage usage via StatFs
        fetchStorageUsage()

        // 9. Initial app list load
        refreshAppList()
    }

    /**
     * Resolves apps from the engine facade into UI AppItem models.
     */
    private fun refreshAppList() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val packageNames = engineFacade.getCurrentAppList()
                val pm = application.packageManager
                val resolvedApps = mutableListOf<AppItem>()

                for (pkg in packageNames) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        val icon = pm.getApplicationIcon(appInfo)
                        val label = pm.getApplicationLabel(appInfo).toString()
                        resolvedApps.add(AppItem(icon = icon, label = label, packageName = pkg))
                    } catch (_: PackageManager.NameNotFoundException) {
                        // Skip uninstalled packages
                    }
                }

                _uiState.update { it.copy(appList = resolvedApps) }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing app list: ${e.message}")
            }
        }
    }

    /**
     * Refreshes weather data from Open-Meteo.
     */
    private suspend fun refreshWeather(lat: Double, lng: Double) {
        val weather = weatherRepository.getWeather(lat, lng)
        if (weather != null) {
            val text = "${weather.temperature}° ${weather.condition}"
            _uiState.update {
                it.copy(
                    weatherText = text,
                    weatherIconRes = weather.iconRes
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    weatherText = "--° --",
                    weatherIconRes = R.drawable.ic_weather_clear
                )
            }
        }
    }

    /**
     * Fetches OpenStreetMap static tile (z=15) with in-memory caching.
     */
    suspend fun fetchMapTile(lat: Double, lng: Double): ImageBitmap? = withContext(Dispatchers.IO) {
        val zoom = 15
        val n = 1 shl zoom
        val tileX = ((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val tileY = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)

        val cacheKey = "$zoom/$tileX/$tileY"
        val cached = mapTileCache.get(cacheKey)
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp < tileTtlMillis)) {
            return@withContext cached.bitmap
        }

        val url = "https://tile.openstreetmap.org/$zoom/$tileX/$tileY.png"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "JupitervisionOS/2112.17 (Android Launcher)")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val bytes = response.body?.bytes() ?: return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val imageBitmap = bitmap.asImageBitmap()

            mapTileCache.put(cacheKey, CachedTile(imageBitmap, now))
            imageBitmap
        } catch (e: Exception) {
            Log.w(TAG, "Map tile fetch failed: ${e.message}")
            null
        }
    }

    private fun registerSystemReceivers() {
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        updateSoundMode(audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL)

        val ringerFilter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        val ringerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val mode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
                updateSoundMode(mode)
            }
        }
        application.registerReceiver(ringerReceiver, ringerFilter)

        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    val percent = (level * 100) / scale
                    _uiState.update { it.copy(batteryPercent = percent) }
                }
            }
        }
        application.registerReceiver(batteryReceiver, batteryFilter)
    }

    private fun updateSoundMode(mode: Int) {
        val modeStr = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "RING"
        }
        _uiState.update { it.copy(soundMode = modeStr) }
    }

    private fun registerNetworkCallback() {
        val cm = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            _uiState.update { it.copy(networkStatus = "OFFLINE") }
            return
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkStatus(cm)
            }

            override fun onLost(network: Network) {
                updateNetworkStatus(cm)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateNetworkStatus(cm)
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            updateNetworkStatus(cm)
        }
    }

    private fun updateNetworkStatus(cm: ConnectivityManager) {
        val active = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(active)
        val status = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "OFFLINE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            else -> "WIFI"
        }
        _uiState.update { it.copy(networkStatus = status) }
    }

    private fun fetchStorageUsage() {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val available = stat.availableBytes
            if (total > 0L) {
                val used = (((total - available) * 100L) / total).toInt()
                _uiState.update { it.copy(storagePercent = used) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Storage stat error: ${e.message}")
        }
    }

    fun onAppClicked(packageName: String) {
        val pm = application.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            application.startActivity(launchIntent)
        }
    }

    fun onScreenLockClicked() {
        val dpm = application.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(application, VisionDeviceAdminReceiver::class.java)

        if (dpm != null && dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
        } else {
            // Prompt user to enable Device Admin policy for screen lock
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Jupitervision OS requires device admin authority to lock the screen."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                application.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch add device admin: ${e.message}")
            }
        }
    }

    fun onUsageAccessClicked() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            application.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun refresh() {
        wallpaperProvider.refresh()
        refreshAppList()
        val venue = engineFacade.currentVenue.value
        if (venue != null) {
            viewModelScope.launch {
                val tile = fetchMapTile(venue.latitude, venue.longitude)
                _uiState.update { it.copy(mapTile = tile) }
                refreshWeather(venue.latitude, venue.longitude)
            }
        }
    }

    companion object {
        private const val TAG = "VisionHomeViewModel"

        private fun formatCurrentTime(): String {
            val sdf = SimpleDateFormat("HH : mm : ss", Locale.getDefault())
            return sdf.format(Date())
        }

        private fun formatCurrentDate(): String {
            val sdf = SimpleDateFormat("EEE, d MMM", Locale.US)
            return sdf.format(Date()).uppercase()
        }
    }
}
