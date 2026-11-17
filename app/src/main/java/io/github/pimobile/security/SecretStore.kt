package io.github.pimobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider API keys, encrypted with an AES-GCM key that never leaves the
 * Android Keystore. Stored as env-var name -> value (e.g. OPENROUTER_API_KEY).
 */
class SecretStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "pi-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun all(): Map<String, String> {
        val blob = prefs.getString("blob", null) ?: return emptyMap()
        return runCatching {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            val json = JSONObject(String(cipher.doFinal(raw, 12, raw.size - 12)))
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun put(name: String, value: String?) {
        val map = all().toMutableMap()
        if (value.isNullOrBlank()) map.remove(name) else map[name] = value.trim()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(JSONObject(map as Map<*, *>).toString().toByteArray())
        prefs.edit().putString("blob", Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP)).apply()
    }
}
