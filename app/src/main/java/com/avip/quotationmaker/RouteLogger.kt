package com.avip.quotationmaker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Field route log for the sales team. The app records the executive's location on working days
 * (9 AM to 8 PM) and the founders (and only the founders) can see each person's day route as a
 * report. Staff are told about this recording in writing, and the app shows a standing notice
 * while it is on, so it is not secret to the person being recorded.
 *
 * Points are kept on the phone first and uploaded in small batches, so a weak signal loses nothing.
 * Nothing records outside the work window, and the office setting "route_logging=off" turns it off
 * for everyone without an app update.
 */
object RouteLog {
    private const val PREFS = "route"
    const val START_HOUR = 9
    const val END_HOUR = 20                 // 8 PM
    private const val MIN_GAP_MS = 150_000L // ~2.5 min between kept points
    private const val MIN_MOVE_M = 40f      // or at least 40 m
    private const val FLUSH_EVERY = 6       // send to the office after this many points
    const val EXTRA_CONSENTED = "routeConsent"

    fun enabled(c: Context): Boolean {
        if (Catalog.settings["route_logging"].equals("off", true)) return false
        return Officer.isComplete(c)
    }

    fun withinWindow(now: Long = System.currentTimeMillis()): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return h in START_HOUR until END_HOUR
    }

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun hasConsented(c: Context) = p(c).getBoolean(EXTRA_CONSENTED, false)
    fun setConsented(c: Context) = p(c).edit().putBoolean(EXTRA_CONSENTED, true).apply()

    /** True once the person has granted at least foreground location (needed to record at all). */
    fun locationGranted(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Keeps one point on the phone (throttled), and queues a batch for the office now and then. */
    fun record(c: Context, lat: Double, lng: Double, at: Long) {
        val pr = p(c)
        val lastAt = pr.getLong("lastAt", 0)
        val lastLat = java.lang.Double.longBitsToDouble(pr.getLong("lastLat", 0))
        val lastLng = java.lang.Double.longBitsToDouble(pr.getLong("lastLng", 0))
        if (lastAt > 0) {
            val d = FloatArray(1); Location.distanceBetween(lastLat, lastLng, lat, lng, d)
            if (at - lastAt < MIN_GAP_MS && d[0] < MIN_MOVE_M) return
        }
        val buf = try { JSONArray(pr.getString("buf", "[]")) } catch (_: Exception) { JSONArray() }
        buf.put(JSONArray().put(lat).put(lng).put(at / 1000))
        pr.edit().putString("buf", buf.toString())
            .putLong("lastAt", at).putLong("lastLat", java.lang.Double.doubleToRawLongBits(lat)).putLong("lastLng", java.lang.Double.doubleToRawLongBits(lng))
            .apply()
        if (buf.length() >= FLUSH_EVERY) flush(c)
    }

    /** Hands the buffered points to the upload queue (sent when there is internet). */
    fun flush(c: Context) {
        val pr = p(c)
        val buf = try { JSONArray(pr.getString("buf", "[]")) } catch (_: Exception) { JSONArray() }
        if (buf.length() == 0) return
        Remote.queue(c, JSONObject().put("kind", "routePing").put("points", buf))
        pr.edit().remove("buf").apply()
    }

    /** Starts or stops the recording service to match the setting, the window and the login. */
    fun ensure(c: Context) {
        scheduleTick(c)
        val on = enabled(c) && withinWindow() && locationGranted(c) && hasConsented(c)
        val i = Intent(c, RouteService::class.java)
        if (on) {
            try { ContextCompat.startForegroundService(c, i) } catch (_: Exception) {}
        } else {
            flush(c)
            try { c.stopService(i) } catch (_: Exception) {}
        }
    }

    /** A gentle repeating check (every ~20 min) so recording starts at 9 and stops at 8. */
    private fun scheduleTick(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 7701,
            Intent(c, LeadReminderReceiver::class.java).setAction("com.avip.quotationmaker.ROUTE_TICK"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1_200_000L, 1_200_000L, pi)
    }
}

/** Foreground service that listens for location while on duty, and self-stops outside the window. */
class RouteService : Service() {
    private var lm: LocationManager? = null
    private val listener = LocationListener { loc -> if (loc.latitude != 0.0 || loc.longitude != 0.0) RouteLog.record(applicationContext, loc.latitude, loc.longitude, System.currentTimeMillis()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotice()
        if (!RouteLog.withinWindow() || !RouteLog.enabled(this) || !RouteLog.locationGranted(this)) { stopSelf(); return START_NOT_STICKY }
        if (lm == null) {
            lm = getSystemService(LOCATION_SERVICE) as LocationManager
            try {
                listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { pr ->
                    if (lm!!.isProviderEnabled(pr)) lm!!.requestLocationUpdates(pr, 120_000L, 30f, listener, mainLooper)
                }
                lm!!.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { RouteLog.record(applicationContext, it.latitude, it.longitude, System.currentTimeMillis()) }
            } catch (_: SecurityException) { stopSelf() }
        }
        return START_STICKY
    }

    private fun startForegroundNotice() {
        val ch = "onduty"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "On duty", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(L("AVIP Solar Sales — on duty"))
            .setContentText(L("Location is recorded on working days (9 AM–8 PM) for route and attendance."))
            .setOngoing(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7702, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(7702, n)
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
        RouteLog.flush(applicationContext)
        super.onDestroy()
    }
}
