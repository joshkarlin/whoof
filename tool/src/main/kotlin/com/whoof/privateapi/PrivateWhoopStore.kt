package com.whoof.privateapi

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.whoof.CredentialCipher
import com.whoof.TokenCipher
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
internal data class PrivateWhoopState(
    val version: Int = 1,
    val session: PrivateWhoopSession? = null,
    val draft: PrivateActivityDraft? = null,
) {
    override fun toString(): String = "PrivateWhoopState(redacted)"
}

internal interface PrivateWhoopStore {
    suspend fun load(): PrivateWhoopState
    suspend fun save(state: PrivateWhoopState)
    suspend fun clear()
}

internal object PrivateWhoopStateCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val MAX_BYTES = 8_192

    fun encode(state: PrivateWhoopState): String = safe {
        validate(state)
        json.encodeToString(state).also { requirePrivate(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(value: String): PrivateWhoopState = safe {
        requirePrivate(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val tree = json.parseToJsonElement(value).jsonObject
        json.decodeFromJsonElement<PrivateWhoopState>(migrateLegacyDraft(tree)).also(::validate)
    }

    /** Drafts saved before the sport catalog stored `"sport":"<Name>"`; keep them (they may be no-replay markers). */
    private fun migrateLegacyDraft(state: JsonObject): JsonObject {
        val draft = state["draft"] as? JsonObject ?: return state
        val legacy = draft["sport"] ?: return state
        val name = legacy.jsonPrimitive.takeIf { it.isString }?.content
        val sport = LEGACY_SPORT_IDS[name]?.let(PrivateWhoopSports::byId)
        requirePrivate(sport != null && "sportId" !in draft && "sportName" !in draft)
        val migrated = buildJsonObject {
            draft.forEach { (key, element) -> if (key != "sport") put(key, element) }
            put("sportId", sport!!.id)
            put("sportName", sport.name)
        }
        return JsonObject(state + ("draft" to migrated))
    }

    private val LEGACY_SPORT_IDS = mapOf("Running" to 0, "Cycling" to 1, "Weightlifting" to 45, "Hiking" to 52, "Walking" to 63)

    private fun validate(state: PrivateWhoopState) {
        requirePrivate(state.version == 1)
        state.draft?.validate(Instant.EPOCH, completedOnly = false)
    }

    private inline fun <T> safe(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw PrivateWhoopException(PrivateWhoopError.StorageFailed)
    }
}

/** Separate preference record; uses the existing audited Keystore/AES-GCM cipher. */
internal class EncryptedPrivateWhoopStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: CredentialCipher = TokenCipher(),
) : PrivateWhoopStore {
    override suspend fun load(): PrivateWhoopState = safe {
        val encoded = dataStore.data.first()[KEY] ?: return@safe PrivateWhoopState()
        requirePrivate(encoded.length <= 12_000)
        PrivateWhoopStateCodec.decode(cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP)))
    }

    override suspend fun save(state: PrivateWhoopState) = safe {
        val encrypted = cipher.encrypt(PrivateWhoopStateCodec.encode(state))
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        requirePrivate(encoded.length <= 12_000)
        dataStore.edit { it[KEY] = encoded }
        Unit
    }

    override suspend fun clear() = safe {
        dataStore.edit { it.remove(KEY) }
        Unit
    }

    private suspend fun <T> safe(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw PrivateWhoopException(PrivateWhoopError.StorageFailed)
    }

    private companion object {
        val KEY = stringPreferencesKey("whoof_private_state_v1")
    }
}
