package com.worksched.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.worksched.data.Schedule
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Location helpers for the optional resume gate. No Google Play Services — uses the platform
 * [LocationManager] only, so the APK stays tiny. Reads are one-shot (a single best-effort fix);
 * continuous monitoring is delegated to the OS via [GeofenceManager] (proximity alerts), so there
 * is no polling.
 */
object LocationGate {

    // ---- permission / availability ----

    fun hasForegroundLocation(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundLocation(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun locationServicesOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    // ---- geometry ----

    /** True when the device [loc] is within the saved radius of the schedule's location. */
    fun isInside(s: Schedule, loc: Location): Boolean {
        val lat = s.latitude ?: return false
        val lng = s.longitude ?: return false
        val r = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, lat, lng, r)
        return r[0] <= s.radiusMeters.toFloat()
    }

    // ---- one-shot best-effort current fix ----

    /**
     * Best-effort current location: one active request via the OS, falling back to the most
     * recent last-known fix. Returns null if no permission, no provider, or it times out.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(ctx: Context, timeoutMs: Long = 8_000L): Location? {
        if (!hasForegroundLocation(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = pickProvider(lm)
        val active: Location? = if (provider != null) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        LocationManagerCompat.getCurrentLocation(
                            lm, provider, signal, ContextCompat.getMainExecutor(ctx)
                        ) { loc -> if (cont.isActive) cont.resume(loc) }
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } else null
        return active ?: lastKnown(lm)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? {
        val provs = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in provs) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
            if (l != null && (best == null || l.time > best.time)) best = l
        }
        return best
    }

    private fun pickProvider(lm: LocationManager): String? {
        val enabled = try { lm.getProviders(true) } catch (e: SecurityException) { emptyList() }
        return when {
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            enabled.isNotEmpty() -> enabled.first()
            else -> null
        }
    }

    // ---- daily resume window (resume time .. pause time on a selected day) ----

    /** True if right now is a selected day and the clock is inside the on-window. */
    fun inResumeWindowNow(s: Schedule): Boolean {
        if (!s.hasValidLocation()) return false
        val now = Calendar.getInstance()
        if (now.get(Calendar.DAY_OF_WEEK) !in s.days) return false
        val mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = s.enableMinutesOfDay()
        val end = s.disableMinutesOfDay()
        return if (start <= end) mins in start until end else (mins >= start || mins < end)
    }

    /**
     * Absolute time the on-window ends — used as the geofence expiration so the OS drops it
     * automatically (bounded battery). Handles an overnight window by rolling to tomorrow.
     */
    fun resumeWindowEndMillis(s: Schedule): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.disableHour)
            set(Calendar.MINUTE, s.disableMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (s.disableMinutesOfDay() <= s.enableMinutesOfDay()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    // ---- reverse geocode: coordinates -> readable address (system Geocoder, needs INTERNET) ----

    /**
     * Best-effort human-readable address for a coordinate. Returns null if the device has no
     * geocoder backend, is offline, or the lookup times out — callers then show coordinates.
     */
    suspend fun describe(ctx: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(ctx, Locale.getDefault())
        val addresses: List<Address>? = runCatching {
            withTimeoutOrNull(5_000L) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(results: MutableList<Address>) {
                                if (cont.isActive) cont.resume(results)
                            }
                            override fun onError(message: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    try { geocoder.getFromLocation(lat, lng, 1) } catch (e: Exception) { null }
                }
            }
        }.getOrNull()
        return addresses?.firstOrNull()?.let { labelOf(it) }
    }

    /**
     * Most precise readable address: prefer the geocoder's full formatted line (building / road /
     * sector / area / city), falling back to a composed premises + street + locality + state if the
     * line isn't available.
     */
    private fun labelOf(a: Address): String? {
        a.getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { return it }
        val locality = a.subLocality ?: a.locality ?: a.subAdminArea
        val parts = listOfNotNull(a.premises ?: a.featureName, a.thoroughfare, locality, a.adminArea)
        return parts.distinct().joinToString(", ").takeIf { it.isNotBlank() }
    }
}
