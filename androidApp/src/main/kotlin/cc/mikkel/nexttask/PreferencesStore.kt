package cc.mikkel.nexttask

import android.content.Context
import cc.mikkel.nexttask.tree.CompletedOrder

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

    private companion object {
        const val KEY_COMPLETED_ORDER = "completed_order"
    }
}
