package cc.sche.apps.nexttask

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

    private companion object {
        const val KEY_COMPLETED_ORDER = "completed_order"
        const val KEY_SELECTED_LIST = "selected_list"
    }
}
