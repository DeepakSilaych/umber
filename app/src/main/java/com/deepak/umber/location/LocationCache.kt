package com.deepak.umber.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.deepak.umber.data.db.LastLocationDao
import com.deepak.umber.data.db.LastLocationEntity
import com.deepak.umber.data.model.LocConfidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class LocationSnapshot(
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Float?,
    val ageMs: Long?,
    val confidence: LocConfidence,
)

/**
 * Foreground-only location tracking, with the last fix cached in the database.
 *
 * The constraint driving this design: transaction ingest happens in a **broadcast receiver**, and
 * on Android 10+ a background process cannot read location without `ACCESS_BACKGROUND_LOCATION` —
 * a permission with a heavy user-consent flow and no real justification for an app that only wants
 * a rough "where was I when I spent this".
 *
 * So instead: while the app is in the foreground, subscribe to the passive and network providers
 * and persist each fix. At ingest time, read that cached row and attach it with an age-derived
 * confidence. No background location permission is ever requested.
 *
 * The honest consequence: for card swipes and in-store UPI this is usually accurate to the minute.
 * For an online purchase made at home it records home, which is true but uninteresting. Anything
 * older than [MEDIUM_MAX_AGE_MS] is stored but should not be presented as fact — see
 * [LocConfidence].
 */
class LocationCache(
    private val context: Context,
    private val dao: LastLocationDao,
    private val scope: CoroutineScope,
) {

    private val manager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    private var tracking = false

    private val listener = LocationListener { location -> record(location) }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** Safe to call repeatedly; a no-op when already tracking or unpermitted. */
    fun startTracking() {
        val lm = manager ?: return
        if (tracking || !hasPermission()) return

        try {
            // PASSIVE costs nothing — it only delivers fixes some other app already paid for.
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    PASSIVE_MIN_INTERVAL_MS,
                    PASSIVE_MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            // NETWORK guarantees a baseline even if nothing else on the device asks for location.
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    NETWORK_MIN_INTERVAL_MS,
                    NETWORK_MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
            }
            tracking = true
            seedFromLastKnown()
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
            tracking = false
        }
    }

    fun stopTracking() {
        if (!tracking) return
        try {
            manager?.removeUpdates(listener)
        } catch (e: SecurityException) {
            // Nothing to clean up.
        }
        tracking = false
    }

    private fun seedFromLastKnown() {
        bestLastKnown()?.let { record(it) }
    }

    private fun bestLastKnown(): Location? {
        val lm = manager ?: return null
        if (!hasPermission()) return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return try {
            providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun record(location: Location) {
        scope.launch {
            dao.put(
                LastLocationEntity(
                    id = 0,
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = if (location.hasAccuracy()) location.accuracy else UNKNOWN_ACCURACY_M,
                    recordedAt = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * The fix to attach to a transaction ingested at [now].
     *
     * Tries a live read first (works when called from the foreground) and falls back to the cached
     * row, taking whichever is fresher.
     */
    suspend fun snapshot(now: Long): LocationSnapshot {
        if (!hasPermission()) return EMPTY

        val cached = dao.get()
        val live = bestLastKnown()

        val cachedAt = cached?.recordedAt ?: Long.MIN_VALUE
        val liveAt = live?.time ?: Long.MIN_VALUE

        val (lat, lon, accuracy, at) = when {
            live != null && liveAt >= cachedAt ->
                Quad(live.latitude, live.longitude, if (live.hasAccuracy()) live.accuracy else UNKNOWN_ACCURACY_M, liveAt)
            cached != null ->
                Quad(cached.lat, cached.lon, cached.accuracyM, cached.recordedAt)
            else -> return EMPTY
        }

        // A fix timestamped in the future is a clock artefact, not a fresh reading.
        val age = (now - at).coerceAtLeast(0L)

        return LocationSnapshot(
            lat = lat,
            lon = lon,
            accuracyM = accuracy,
            ageMs = age,
            confidence = confidenceFor(age),
        )
    }

    private fun confidenceFor(ageMs: Long): LocConfidence = when {
        ageMs <= HIGH_MAX_AGE_MS -> LocConfidence.HIGH
        ageMs <= MEDIUM_MAX_AGE_MS -> LocConfidence.MEDIUM
        ageMs <= LOW_MAX_AGE_MS -> LocConfidence.LOW
        else -> LocConfidence.NONE
    }

    private data class Quad(val a: Double, val b: Double, val c: Float, val d: Long)

    companion object {
        private const val PASSIVE_MIN_INTERVAL_MS = 60_000L
        private const val PASSIVE_MIN_DISTANCE_M = 50f
        private const val NETWORK_MIN_INTERVAL_MS = 120_000L
        private const val NETWORK_MIN_DISTANCE_M = 100f

        private const val UNKNOWN_ACCURACY_M = 9999f

        const val HIGH_MAX_AGE_MS = 2 * 60_000L
        const val MEDIUM_MAX_AGE_MS = 10 * 60_000L
        const val LOW_MAX_AGE_MS = 60 * 60_000L

        val EMPTY = LocationSnapshot(null, null, null, null, LocConfidence.NONE)
    }
}
