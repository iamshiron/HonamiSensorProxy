package io.shiron.honamisensorproxy.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Composable
actual fun rememberProfileStore(): ProfileStore {
    val context = LocalContext.current
    return remember { AndroidProfileStore(context.applicationContext) }
}

/**
 * Stores the profile in private [android.content.SharedPreferences]; the bearer token is
 * encrypted with an AES-256-GCM key held in the AndroidKeyStore, so the plaintext secret never
 * touches disk (§14).
 */
private class AndroidProfileStore(context: Context) : ProfileStore {
    private val prefs = context.getSharedPreferences("hsp_profile", Context.MODE_PRIVATE)

    override suspend fun load(): Profile? = withContext(Dispatchers.IO) {
        val url = prefs.getString(KEY_URL, null) ?: return@withContext null
        val name = prefs.getString(KEY_NAME, "") ?: ""
        val token = prefs.getString(KEY_TOKEN, null)
            ?.let { runCatching { TokenCipher.decrypt(it) }.getOrNull() }
            ?: ""
        Profile(name = name, ingestUrl = url, token = token)
    }

    override suspend fun save(profile: Profile): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_NAME, profile.name)
            .putString(KEY_URL, profile.ingestUrl)
            .putString(KEY_TOKEN, if (profile.token.isBlank()) null else TokenCipher.encrypt(profile.token))
            .apply()
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
    }
}

/** AES-256-GCM encryption with a non-exportable key in the AndroidKeyStore. */
private object TokenCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "hsp_profile_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        val combined = cipher.iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(blob: String): String {
        val combined = Base64.decode(blob, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }
}
