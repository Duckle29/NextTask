package cc.sche.apps.nexttask

import android.content.Context
import cc.sche.apps.nexttask.sync.AccountSnapshot
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps the local copy of the account's tasks in the app's private storage, so the app opens instantly
 * and works without a connection. The file is excluded from backups: the server is the source of truth.
 */
class SnapshotStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The saved copy, or null if there is none or it cannot be read (it is then rebuilt from the server). */
    fun load(): AccountSnapshot? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<AccountSnapshot>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Replaces the saved copy atomically, so a crash mid-write never leaves a torn file. */
    fun save(snapshot: AccountSnapshot) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(snapshot))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "account_snapshot.json"
    }
}
