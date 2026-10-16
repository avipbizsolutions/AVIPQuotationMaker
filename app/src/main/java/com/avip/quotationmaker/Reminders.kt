package com.avip.quotationmaker

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Follow-up call reminders: a notification at 10 AM on the chosen day with a
 * "Call now" button. Alarms are re-set after the phone restarts or the app updates.
 */
object Reminders {
    private const val CHANNEL = "followups"
    const val EXTRA_NUMBER = "number"

    /** 10:00 AM on the day of [dayMillis]. */
    fun tenAm(dayMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayMillis
        set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pending(c: Context, number: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            c, number.hashCode(),
            Intent(c, ReminderReceiver::class.java).setAction("com.avip.quotationmaker.FOLLOWUP").putExtra(EXTRA_NUMBER, number),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(c: Context, v: Visit) {
        cancel(c, v.number)
        if (v.followUpAt <= 0 || v.leadStatus != "Open") return
        // a date in the past (e.g. "today" after 10 AM) rings a minute from now
        val at = maxOf(v.followUpAt, System.currentTimeMillis() + 60_000L)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, v.number, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(c: Context, number: String) {
        val pi = pending(c, number, PendingIntent.FLAG_NO_CREATE) ?: return
        c.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    /** After a restart: set every future reminder again. */
    fun rescheduleAll(c: Context) {
        VisitStore.all(c).filter { it.followUpAt > 0 && it.leadStatus == "Open" }.forEach { schedule(c, it) }
    }

    fun notify(c: Context, v: Visit) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Follow-up calls", NotificationManager.IMPORTANCE_HIGH)
        )
        val id = v.number.hashCode()
        val open = PendingIntent.getActivity(
            c, id,
            Intent(c, VisitActivity::class.java).putExtra(VisitActivity.EXTRA_NUMBER, v.number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val call = PendingIntent.getActivity(
            c, id + 1,
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + v.mobile.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val system = v.pkg?.let { "${Fmt.one(it.kwp)} kWp ${it.type.label} · ${Fmt.inr(v.finalPrice)}" } ?: "Site visit ${v.number}"
        val text = listOf(system, v.followUpNote).filter { it.isNotBlank() }.joinToString("\n")
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Follow-up call: ${v.name}")
            .setContentText(system)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(R.drawable.ic_call, "Call ${v.mobile}", call)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) {}
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.rescheduleAll(context)
            else -> {
                val number = intent.getStringExtra(Reminders.EXTRA_NUMBER) ?: return
                val v = VisitStore.load(context, number) ?: return
                if (v.leadStatus == "Open" && v.followUpAt > 0) Reminders.notify(context, v)
            }
        }
    }
}
