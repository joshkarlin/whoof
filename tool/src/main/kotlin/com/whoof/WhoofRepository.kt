package com.whoof

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single access point shared by every screen so that token refresh stays process-wide. */
internal interface WhoofRepository {
    val days: Flow<List<DaySummary>>

    val lastSuccessfulSyncMillis: Flow<Long?>

    suspend fun hasCredentials(): Boolean

    /** Stores new credentials and drops any previous account's cached records. */
    suspend fun provision(credentials: WhoopCredentials)

    suspend fun sync()

    /** Removes credentials, cached records, and synchronization metadata from this device. */
    suspend fun disconnect()
}

internal class DefaultWhoofRepository(
    private val credentialStore: WhoopCredentialStore,
    private val cache: WhoopCache,
    private val metadata: SyncMetadataStore,
    api: WhoopHistoryClient = WhoopApi(),
) : WhoofRepository {
    private val synchronizer = WhoopSynchronizer(
        api = api,
        tokenCoordinator = WhoopTokenCoordinator(credentialStore, api),
        cache = cache,
        metadata = metadata,
    )

    override val days: Flow<List<DaySummary>> = cache.observe().map(::buildDaySummaries)

    override val lastSuccessfulSyncMillis: Flow<Long?> = metadata.lastSuccessfulSyncMillis

    override suspend fun hasCredentials(): Boolean = credentialStore.load() != null

    // Both run under the sync lock so an in-flight refresh cannot write rotated tokens or records
    // for the previous account after they have been replaced or deleted.
    override suspend fun provision(credentials: WhoopCredentials) = synchronizer.exclusive {
        cache.clear()
        metadata.clear()
        credentialStore.save(credentials)
    }

    override suspend fun sync() = synchronizer.sync()

    override suspend fun disconnect() = synchronizer.exclusive {
        credentialStore.clear()
        cache.clear()
        metadata.clear()
    }

    companion object {
        @Volatile
        private var instance: WhoofRepository? = null

        fun getInstance(context: SealedLightContext): WhoofRepository =
            instance ?: synchronized(this) {
                instance ?: DefaultWhoofRepository(
                    credentialStore = TokenStore(context.dataStore),
                    cache = RoomWhoopCache(
                        context.buildDatabase(WhoofDatabase::class.java, WHOOF_DATABASE_NAME),
                    ),
                    metadata = DataStoreSyncMetadata(context.dataStore),
                ).also { instance = it }
            }
    }
}
