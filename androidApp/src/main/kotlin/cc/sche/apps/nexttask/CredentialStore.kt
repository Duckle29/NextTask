package cc.sche.apps.nexttask

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cc.sche.apps.nexttask.auth.Credentials
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists [Credentials] encrypted with a non-exportable Android Keystore key. From Android 9 the key only
 * works while the device is unlocked, so the app password cannot be decrypted from a locked phone.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun load(): Credentials? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        // Credentials saved before the key alias was recorded are encrypted with the legacy key.
        val alias = prefs.getString(KEY_KEY_ALIAS, LEGACY_KEY_ALIAS)
        val credentials = runCatching {
            val key = keyStore().getKey(alias, null) as SecretKey
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).decodeToString()
            Json.decodeFromString(Credentials.serializer(), plain)
        }.getOrNull() ?: return null // An unreadable blob (e.g. key lost, or an older format) is treated as logged out.
        if (alias == LEGACY_KEY_ALIAS) migrateFromLegacyKey(credentials)
        return credentials
    }

    /** True if credentials are stored, without decrypting them. */
    val isSignedIn: Boolean get() = prefs.contains(KEY_BLOB)

    fun save(credentials: Credentials) {
        write(credentials).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Re-encrypts [credentials] with the current key, then deletes the legacy one. Written through at once:
     * the legacy key must only go once nothing needs it. If this fails, the legacy copy stays and is retried next time.
     */
    @SuppressLint("ApplySharedPref")
    private fun migrateFromLegacyKey(credentials: Credentials) {
        runCatching {
            check(write(credentials).commit()) { "Could not write the credentials" }
            keyStore().deleteEntry(LEGACY_KEY_ALIAS)
        }
    }

    private fun write(credentials: Credentials) = run {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plain = Json.encodeToString(Credentials.serializer(), credentials)
        val blob = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        prefs.edit()
            .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString(KEY_KEY_ALIAS, KEY_ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setUnlockedDeviceRequired(true) }
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        /** Created before keys were tied to an unlocked device; only read, to move its credentials over. */
        const val LEGACY_KEY_ALIAS = "nexttask_credentials"
        const val KEY_ALIAS = "nexttask_credentials_unlocked"
        const val KEY_BLOB = "blob"
        const val KEY_KEY_ALIAS = "key_alias"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
