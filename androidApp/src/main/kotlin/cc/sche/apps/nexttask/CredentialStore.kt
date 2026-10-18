package cc.sche.apps.nexttask

import android.content.Context
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

/** Persists [Credentials] encrypted with a non-exportable Android Keystore key. */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun load(): Credentials? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
            val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).decodeToString()
            Json.decodeFromString(Credentials.serializer(), plain)
        }.getOrNull() // An unreadable blob (e.g. key lost, or an older format) is treated as logged out.
    }

    fun save(credentials: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plain = Json.encodeToString(Credentials.serializer(), credentials)
        val blob = cipher.iv + cipher.doFinal(plain.encodeToByteArray())
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nexttask_credentials"
        const val KEY_BLOB = "blob"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
