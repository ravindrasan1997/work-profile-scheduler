package com.worksched.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log

/**
 * Event-driven geofence around the saved location, via the platform
 * [LocationManager.addProximityAlert]. The OS monitors the boundary and broadcasts a one-shot
 * intent on enter/exit — no polling, no Google Play Services, no API key. Armed only inside the
 * work window (with an expiration), so monitoring is bounded.
 */
object GeofenceManager {

    const val ACTION_GEOFENCE = "com.worksched.action.GEOFENCE"
    private const val TAG = "WorkSchedGeofence"
    private const val REQUEST_CODE = 7000

    private fun pendingIntent(ctx: Context): PendingIntent {
        // Package-scoped ACTION intent (not an explicit component): addProximityAlert requires the
        // PendingIntent be "targeted to a package", which means getPackage() != null. setPackage +
        // the GeofenceReceiver's manifest intent-filter route it to our (exported=false) receiver.
        val intent = Intent(ACTION_GEOFENCE).setPackage(ctx.packageName)
        // MUST be mutable: the OS injects KEY_PROXIMITY_ENTERING into this PendingIntent when it
        // fires (GeofenceReceiver reads it). FLAG_MUTABLE is a no-op pre-API-31 (mutable default).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, flags)
    }

    /**
     * Register a proximity alert. [expirationAtMs] is an absolute wall-clock time at which the OS
     * drops the alert (the day's pause time); converted to a duration here. Returns true if armed.
     */
    @SuppressLint("MissingPermission")
    fun arm(ctx: Context, lat: Double, lng: Double, radiusMeters: Int, expirationAtMs: Long): Boolean {
        if (!LocationGate.hasForegroundLocation(ctx)) {
            Log.w(TAG, "arm skipped — no location permission")
            return false
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val duration = (expirationAtMs - System.currentTimeMillis())
        val expiration = if (duration > 0) duration else -1L // -1 = never expire (safety)
        return try {
            lm.addProximityAlert(lat, lng, radiusMeters.toFloat(), expiration, pendingIntent(ctx))
            Log.i(TAG, "armed proximity alert r=${radiusMeters}m expires in ${expiration / 1000}s")
            true
        } catch (e: Throwable) {
            // Never let an arm failure crash the receiver coroutine.
            Log.w(TAG, "arm failed: $e")
            false
        }
    }

    fun disarm(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.removeProximityAlert(pendingIntent(ctx))
            Log.i(TAG, "disarmed proximity alert")
        } catch (e: Throwable) {
            Log.w(TAG, "disarm failed: $e")
        }
    }
}
