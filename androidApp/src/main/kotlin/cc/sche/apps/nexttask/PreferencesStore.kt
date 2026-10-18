package cc.sche.apps.nexttask

import android.annotation.SuppressLint
import android.content.Context
import cc.sche.apps.nexttask.tree.CompletedOrder

/** App settings, persisted in plain SharedPreferences (nothing sensitive lives here). */
class PreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    var completedOrder: CompletedOrder
        get() = prefs.getString(KEY_COMPLETED_ORDER, null)
            ?.let { name -> CompletedOrder.entries.firstOrNull { it.name == name } }
            ?: CompletedOrder.Everywhere
        set(value) {
            prefs.edit().putString(KEY_COMPLETED_ORDER, value.name).apply()
        }

    /** The task list the user was looking at; the first list is used when this is unset or no longer exists. */
    var selectedList: String?
        get() = prefs.getString(KEY_SELECTED_LIST, null)
        set(value) {
            prefs.edit().putString(KEY_SELECTED_LIST, value).apply()
        }

    /** Whether task reminders are shown as notifications on this device. Off by default: see [ReminderScheduler]. */
    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDERS_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, value).apply()
        }

    /** Reminders up to this moment (epoch milliseconds) have been shown, so they are never shown twice. */
    var remindersShownUntil: Long
        get() = prefs.getLong(KEY_REMINDERS_SHOWN_UNTIL, 0)
        // Written through at once (callers are off the main thread): the process may end right after.
        @SuppressLint("ApplySharedPref")
        set(value) {
            prefs.edit().putLong(KEY_REMINDERS_SHOWN_UNTIL, value).commit()
        }

    private companion object {
        const val KEY_COMPLETED_ORDER = "completed_order"
        const val KEY_SELECTED_LIST = "selected_list"
        const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        const val KEY_REMINDERS_SHOWN_UNTIL = "reminders_shown_until"
    }
}
