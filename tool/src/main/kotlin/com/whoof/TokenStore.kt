package com.whoof

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher = TokenCipher(),
) {
    private val json = Json

    suspend fun load(): WhoopCredentials? {
        val encoded = dataStore.data.first()[TOKEN_KEY] ?: return null
        return json.decodeFromString(cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP)))
    }

    suspend fun save(credentials: WhoopCredentials) {
        val encoded = Base64.encodeToString(
            cipher.encrypt(json.encodeToString(credentials)),
            Base64.NO_WRAP,
        )
        dataStore.edit { it[TOKEN_KEY] = encoded }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(TOKEN_KEY) }
    }

    private companion object {
        val TOKEN_KEY = stringPreferencesKey("whoop_credentials_v1")
    }
}

internal class TokenCipher {
    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
    }

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    fun decrypt(blob: ByteArray): String {
        val buffer = ByteBuffer.wrap(blob)
        val ivLength = buffer.get().toInt()
        require(ivLength in 12..16 && buffer.remaining() > ivLength) { "Invalid encrypted token." }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey =
        (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "whoof_oauth_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
