// LocationRepository.kt - Low-power passive location tracking and venue discovery engine.
package com.jupiter.vision.engine.data.repository

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.jupiter.vision.engine.data.dao.VisionDao
import com.jupiter.vision.engine.data.entities.VenueEntity
import com.jupiter.vision.engine.data.entities.VisitLogEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: VisionDao,
    private val fusedLocationClient: FusedLocationProviderClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _currentVenue = MutableStateFlow<VenueEntity?>(null)
    val currentVenue: StateFlow<VenueEntity?> = _currentVenue.asStateFlow()

    private val _lastLocation = MutableStateFlow<Location?>(null)
    val lastLocation: StateFlow<Location?> = _lastLocation.asStateFlow()

    private var lastMovementTimestamp: Long = System.currentTimeMillis()
    private var isTracking = false

    // Candidate visit tracker for automatic new venue creation
    private data class CandidateVisit(
        val latitude: Double,
        val longitude: Double,
        val firstDetectedTime: Long,
        var lastUpdatedTime: Long
    )

    private var candidateVisit: CandidateVisit? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleNewLocation(location)
        }
    }

    /**
     * Checks location permissions without prompting the user.
     */
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Starts passive location updates every 2 minutes (balanced power accuracy).
     */
    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking || !hasLocationPermission()) return

        try {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                120_000L // 2 minutes polling
            ).setMinUpdateIntervalMillis(60_000L) // Min 1 minute
                .setMaxUpdateDelayMillis(180_000L) // Batching allowed for battery saving
                .build()

            fusedLocationClient.requestLocationUpdates(
                request,
                locationCallback,
                Looper.getMainLooper()
            )
            isTracking = true
            Log.d(TAG, "LocationRepository tracking started (interval: 120s)")
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission missing when starting location tracking: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location tracking: ${e.message}")
        }
    }

    /**
     * Stops location updates to preserve battery when idle.
     */
    fun stopTracking() {
        if (!isTracking) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isTracking = false
            Log.d(TAG, "LocationRepository tracking stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates: ${e.message}")
        }
    }

    /**
     * One-shot current location query.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        return suspendCancellableCoroutine { cont ->
            try {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * Process each incoming location fix.
     */
    private fun handleNewLocation(location: Location) {
        val now = System.currentTimeMillis()

        // Track movement distance to determine stationary duration
        val previous = _lastLocation.value
        if (previous != null) {
            val movedMeters = location.distanceTo(previous)
            if (movedMeters > 30f) {
                lastMovementTimestamp = now
            }
        } else {
            lastMovementTimestamp = now
        }
        _lastLocation.value = location

        scope.launch {
            processVenueAndVisit(location, now)
        }
    }

    private suspend fun processVenueAndVisit(location: Location, now: Long) {
        val nearestPair = dao.getNearestVenue(location.latitude, location.longitude)
        val nearestVenue = nearestPair?.first
        val distanceMeters = nearestPair?.second ?: Float.MAX_VALUE

        if (nearestVenue != null && distanceMeters <= nearestVenue.radiusMeters) {
            // User is inside an established venue
            candidateVisit = null // Discard any candidate search

            val prevVenue = _currentVenue.value
            _currentVenue.value = nearestVenue

            // Ensure an open visit log exists for this venue
            val openVisit = dao.getOpenVisitForVenue(nearestVenue.id)
            if (openVisit == null) {
                // Close any other open visits
                dao.closeAllOpenVisits(now)
                // Open new visit log
                dao.insertVisit(
                    VisitLogEntity(
                        venueId = nearestVenue.id,
                        arrivalTime = now,
                        departureTime = null
                    )
                )
                // Increment visit count on arrival
                dao.incrementVisitCount(nearestVenue.id, now)
            } else {
                dao.updateVenueLastSeen(nearestVenue.id, now)
            }
        } else {
            // User is not within any existing venue
            _currentVenue.value = null

            // Close any currently open visits
            dao.closeAllOpenVisits(now)

            // Evaluate or update candidate visit tracker
            evaluateCandidateVisit(location, now)
        }
    }

    /**
     * If the user stays within 100m for 15+ minutes, automatically create a new VenueEntity.
     */
    private suspend fun evaluateCandidateVisit(location: Location, now: Long) {
        val currentCandidate = candidateVisit

        if (currentCandidate == null) {
            candidateVisit = CandidateVisit(
                latitude = location.latitude,
                longitude = location.longitude,
                firstDetectedTime = now,
                lastUpdatedTime = now
            )
        } else {
            val dist = VisionDao.computeDistanceMeters(
                location.latitude, location.longitude,
                currentCandidate.latitude, currentCandidate.longitude
            )

            if (dist <= 100f) {
                currentCandidate.lastUpdatedTime = now
                val dwellDuration = now - currentCandidate.firstDetectedTime
                val fifteenMinutes = 15 * 60 * 1000L

                if (dwellDuration >= fifteenMinutes) {
                    // Dwell threshold reached: Establish a new venue
                    val currentCount = dao.getVenueCount()
                    val venueLabel = "LOCATION ${currentCount + 1}"

                    val newVenueId = dao.insertVenue(
                        VenueEntity(
                            latitude = currentCandidate.latitude,
                            longitude = currentCandidate.longitude,
                            radiusMeters = 100f,
                            inferredType = "UNKNOWN",
                            label = venueLabel,
                            firstSeen = currentCandidate.firstDetectedTime,
                            lastSeen = now,
                            visitCount = 1
                        )
                    )

                    // Insert initial visit log
                    dao.insertVisit(
                        VisitLogEntity(
                            venueId = newVenueId,
                            arrivalTime = currentCandidate.firstDetectedTime,
                            departureTime = null
                        )
                    )

                    val createdVenue = dao.getVenueById(newVenueId)
                    _currentVenue.value = createdVenue
                    candidateVisit = null

                    Log.d(TAG, "Created new inferred venue: $venueLabel (id=$newVenueId)")
                }
            } else {
                // Moved outside 100m radius -> restart candidate tracker
                candidateVisit = CandidateVisit(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    firstDetectedTime = now,
                    lastUpdatedTime = now
                )
            }
        }
    }

    /**
     * Milliseconds elapsed since the user last moved > 30 meters.
     */
    fun getTimeSinceLastMovement(): Long {
        return System.currentTimeMillis() - lastMovementTimestamp
    }

    companion object {
        private const val TAG = "LocationRepository"
    }
}
