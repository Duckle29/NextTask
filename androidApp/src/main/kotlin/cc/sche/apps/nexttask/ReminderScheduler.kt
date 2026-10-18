package cc.sche.apps.nexttask

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.WorkerThread
import cc.sche.apps.nexttask.sync.AccountSnapshot
import java.time.Instant
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Shows task reminders as notifications, if the user turned them on.
 *
 * Nextcloud itself only sends reminders for events, not tasks, so this is the only place they surface; it is
 * still off by default, because another task app on the device (e.g. Tasks.org via DAVx⁵) may already show them.
 *
 * One alarm is kept, for the next reminder. When it goes off, [update] shows every reminder that has come
 * due since the last time and sets the alarm for the one after. The reminders come from the local copy, so
 * they are as current as the last sync.
 */
class ReminderScheduler(context: Context) {

    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val preferences = PreferencesStore(this.context)

    /** True if reminders arrive on time; otherwise Android may hold them back for a few minutes. */
    val canRemindOnTime: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    val canNotify: Boolean get() = notifications.areNotificationsEnabled()

    /** Turns reminders on or off. Reminders already past are not shown when turning them on. */
    @WorkerThread
    fun setEnabled(enabled: Boolean, snapshot: AccountSnapshot?) = synchronized(LOCK) {
        preferences.remindersEnabled = enabled
        if (enabled) preferences.remindersShownUntil = System.currentTimeMillis()
        update(snapshot)
    }

    /**
     * Shows the reminders that have come due since the last call and sets the alarm for the next one. Call it
     * whenever the tasks change; with reminders off or no account ([snapshot] null) it removes the alarm.
     */
    @WorkerThread
    fun update(snapshot: AccountSnapshot?): Unit = synchronized(LOCK) {
        if (!preferences.remindersEnabled || snapshot == null) {
            alarms.cancel(alarmIntent())
            return
        }
        val now = Instant.now()
        val shownUntil = Instant.ofEpochMilli(preferences.remindersShownUntil)
        val reminders = snapshot.reminders(ZoneId.systemDefault())
        reminders.filter { it.at > shownUntil && it.at <= now }.forEach(::show)
        preferences.remindersShownUntil = now.toEpochMilli()

        val next = reminders.firstOrNull { it.at > now }
        if (next == null) {
            alarms.cancel(alarmIntent())
        } else if (canRemindOnTime) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toEpochMilli(), alarmIntent())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toEpochMilli(), alarmIntent())
        }
    }

    /** Removes the alarm and every reminder on screen, e.g. on sign-out. */
    fun clear() = synchronized(LOCK) {
        alarms.cancel(alarmIntent())
        notifications.cancelAll()
    }

    private fun show(reminder: Reminder) {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_HIGH),
        )
        // One notification per task: a later reminder of the same task replaces the earlier one.
        val id = (reminder.listHref + "\n" + reminder.uid).hashCode()
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_LIST, reminder.listHref)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.summary)
            .setContentText(reminder.listName)
            .setWhen(reminder.at.toEpochMilli())
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        notifications.notify(id, notification)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_REMIND = "cc.sche.apps.nexttask.action.REMIND"
        private const val CHANNEL = "reminders"

        /** The app and the alarm can update at the same moment; one at a time keeps "shown until" consistent. */
        private val LOCK = Any()
    }
}

/**
 * Runs [ReminderScheduler.update] when a reminder's alarm goes off, and when the alarm needs setting again:
 * after a reboot, a change of time or time zone, an app update, or when exact alarms are allowed.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val result = goAsync()
        thread(name = "reminders") {
            try {
                // Only the signed-in account's tasks: after a sign-out the local copy may remain until the next sign-in.
                val snapshot = SnapshotStore(context).load()?.takeIf { CredentialStore(context).isSignedIn }
                ReminderScheduler(context).update(snapshot)
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        // The exact-alarm action is from Android 12; before that it is simply never sent.
        @SuppressLint("InlinedApi")
        val HANDLED_ACTIONS = setOf(
            ReminderScheduler.ACTION_REMIND,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
