package ru.nstu.mail.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(credentials: Credentials) {
        val json = JSONObject().apply {
            put("email", credentials.email)
            put("password", credentials.password)
            put("serverUrl", credentials.serverUrl)
        }.toString()

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Credentials? {
        val ivB64 = prefs.getString(KEY_IV, null) ?: return null
        val dataB64 = prefs.getString(KEY_DATA, null) ?: return null
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val json = String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
            val obj = JSONObject(json)
            val email = obj.getString("email")
            val password = obj.getString("password")
            val serverUrl = obj.optString("serverUrl", DEFAULT_SERVER_URL)
            if (email.isBlank() || password.isEmpty()) null
            else Credentials(email = email, password = password, serverUrl = serverUrl)
        }.getOrElse { err ->
            val corrupted = err is AEADBadTagException ||
                err is BadPaddingException ||
                err is IllegalBlockSizeException ||
                err is IllegalArgumentException ||
                err is org.json.JSONException
            if (corrupted) {
                clear()
            }
            null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_DATA).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nstu_mail_cred_key"
        const val PREFS_NAME = "nstu_mail_creds"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
