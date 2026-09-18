package nl.tippie.subtitle.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys encrypted with an AES-256-GCM key that lives in the Android Keystore and never
 * leaves it. The ciphertext sits in a SharedPreferences file that is explicitly excluded
 * from backup and device transfer, so a restored backup cannot leak the key.
 *
 * Written directly against Keystore rather than androidx.security:security-crypto:
 * EncryptedSharedPreferences is deprecated in current releases of that library, and this
 * is ~60 lines with no dependency and no migration story to inherit.
 */
class ApiKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(encrypted, 0, packed, 1 + iv.size, encrypted.size)
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val ivLen = packed[0].toInt()
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val encrypted = packed.copyOfRange(1 + ivLen, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()   // key invalidated (e.g. device wiped): behave as "not set"
    }

    fun has(name: String): Boolean = !get(name).isNullOrBlank()

    fun clear() = prefs.edit().clear().apply()

    /** For display: `sk-proj…4f2a`. Never logs or shows the full value. */
    fun masked(name: String): String? {
        val value = get(name) ?: return null
        if (value.length <= 10) return "•".repeat(value.length)
        return "${value.take(6)}…${value.takeLast(4)}"
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val OPENAI = "openai_api_key"
        const val BACKEND = "backend_api_key"

        private const val PREFS_NAME = "secure_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nl.tippie.subtitle.apikeys.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
