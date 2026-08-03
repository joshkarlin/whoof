package com.whoof

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class TokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher = TokenCipher(),
) : WhoopCredentialStore {
    override suspend fun load(): WhoopCredentials? {
        val encoded = dataStore.data.first()[TOKEN_KEY] ?: return null
        require(encoded.length <= MAX_BASE64_BLOB_LENGTH) { "Stored credentials are invalid." }
        val encrypted = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrElse { throw IllegalArgumentException("Stored credentials are invalid.") }
        return StoredCredentialsCodec.decode(cipher.decrypt(encrypted))
    }

    override suspend fun save(credentials: WhoopCredentials) {
        val serialized = StoredCredentialsCodec.encode(credentials)
        val encoded = Base64.encodeToString(
            cipher.encrypt(serialized),
            Base64.NO_WRAP,
        )
        require(encoded.length <= MAX_BASE64_BLOB_LENGTH) { "Stored credentials are too large." }
        dataStore.edit { it[TOKEN_KEY] = encoded }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(TOKEN_KEY) }
    }

    private companion object {
        val TOKEN_KEY = stringPreferencesKey("whoop_credentials_v1")
    }
}

internal interface WhoopCredentialStore {
    suspend fun load(): WhoopCredentials?

    suspend fun save(credentials: WhoopCredentials)

    suspend fun clear()
}

internal object StoredCredentialsCodec {
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = false
    }

    fun encode(credentials: WhoopCredentials): String {
        CredentialValidation.validate(credentials)
        return json.encodeToString(credentials).also(::validateSerialized)
    }

    fun decode(serialized: String): WhoopCredentials {
        validateSerialized(serialized)
        return runCatching { json.decodeFromString<WhoopCredentials>(serialized) }
            .getOrElse { throw IllegalArgumentException("Stored credentials are invalid.") }
            .also(CredentialValidation::validate)
    }

    private fun validateSerialized(serialized: String) {
        require(
            serialized.toByteArray(StandardCharsets.UTF_8).size <= CredentialValidation.MAX_STORED_JSON_BYTES,
        ) { "Stored credentials are too large." }
    }
}

internal interface CredentialCipher {
    fun encrypt(plaintext: String): ByteArray

    fun decrypt(blob: ByteArray): String
}

internal class TokenCipher : CredentialCipher {
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

    override fun encrypt(plaintext: String): ByteArray {
        val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(plaintextBytes.size <= CredentialValidation.MAX_STORED_JSON_BYTES) {
            "Stored credentials are too large."
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintextBytes)
        return ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    override fun decrypt(blob: ByteArray): String {
        require(blob.size in MIN_ENCRYPTED_BLOB_LENGTH..MAX_ENCRYPTED_BLOB_LENGTH) {
            "Invalid encrypted token."
        }
        val buffer = ByteBuffer.wrap(blob)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in GCM_IV_MIN_BYTES..GCM_IV_MAX_BYTES &&
            buffer.remaining() >= ivLength + GCM_TAG_BYTES) {
            "Invalid encrypted token."
        }
        val iv = ByteArray(ivLength).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey =
        (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "whoof_oauth_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val GCM_IV_MIN_BYTES = 12
        const val GCM_IV_MAX_BYTES = 16
        const val MIN_ENCRYPTED_BLOB_LENGTH = 1 + GCM_IV_MIN_BYTES + GCM_TAG_BYTES
        const val MAX_ENCRYPTED_BLOB_LENGTH =
            1 + GCM_IV_MAX_BYTES + CredentialValidation.MAX_STORED_JSON_BYTES + GCM_TAG_BYTES
    }
}

private const val MAX_BASE64_BLOB_LENGTH =
    ((1 + 16 + CredentialValidation.MAX_STORED_JSON_BYTES + 16 + 2) / 3) * 4
