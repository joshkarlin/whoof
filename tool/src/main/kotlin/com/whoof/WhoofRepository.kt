package com.whoof

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import com.whoof.privateapi.PrivateWhoopServices
import com.whoof.privateapi.WhoopAccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Single access point shared by every screen so that the account session stays process-wide. */
internal interface WhoofRepository {
    val days: Flow<List<DaySummary>>

    val lastSuccessfulSyncMillis: Flow<Long?>

    /** True when a WHOOP account session is stored on this phone. */
    suspend fun isSignedIn(): Boolean

    suspend fun sync()

    /** Records an activity just submitted from this phone so Today shows it before WHOOP lists it. */
    suspend fun addPendingActivity(activity: PendingActivityEntity)

    /** Drops one workout, and any pending copy of it, from the local cache after it was deleted from WHOOP. */
    suspend fun removeCachedWorkout(id: String)

    /**
     * Ends the WHOOP session and removes cached records, sync metadata, the activity timer, and
     * recent sports, favourites, and pending activities. A prepared-then-uncertain activity submission marker is kept so signing back in
     * can never silently replay it.
     */
    suspend fun signOut()

    /** [signOut] plus removal of everything else stored for the account, including that marker. */
    suspend fun deleteLocalData()
}

internal class DefaultWhoofRepository(
    private val session: WhoopAccountSession,
    private val cache: WhoopCache,
    private val metadata: SyncMetadataStore,
    private val activityPrefs: ActivityPrefs,
    private val pending: PendingActivityStore,
    api: WhoopHistoryClient = WhoopApi(),
) : WhoofRepository {
    private val synchronizer = WhoopSynchronizer(
        api = api,
        session = session,
        cache = cache,
        metadata = metadata,
    )

    override val days: Flow<List<DaySummary>> =
        combine(cache.observe(), pending.observe()) { snapshot, local -> buildDaySummaries(snapshot, local) }

    override val lastSuccessfulSyncMillis: Flow<Long?> = metadata.lastSuccessfulSyncMillis

    override suspend fun isSignedIn(): Boolean = session.isSignedIn()

    override suspend fun sync() {
        synchronizer.sync()
        prunePending()
    }

    override suspend fun addPendingActivity(activity: PendingActivityEntity) = pending.add(activity)

    override suspend fun removeCachedWorkout(id: String) = synchronizer.exclusive {
        cache.deleteWorkout(id)
        pending.remove(listOf(id))
    }

    /** Forgets pending activities WHOOP now lists, and any older than 48 hours. Never fails a sync. */
    private suspend fun prunePending() {
        try {
            val stale = stalePendingIds(
                pending.all(),
                cache.observe().first().workouts.map { it.id },
                System.currentTimeMillis(),
            )
            pending.remove(stale)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Display already ignores stale rows; the next sync retries.
        }
    }

    // Both run under the sync lock so an in-flight sync cannot write records for the old account
    // after they have been removed.
    override suspend fun signOut() = synchronizer.exclusive {
        session.disconnect()
        clearLocalRecords()
    }

    override suspend fun deleteLocalData() = synchronizer.exclusive {
        session.deleteLocalData(confirmed = true)
        clearLocalRecords()
    }

    private suspend fun clearLocalRecords() {
        cache.clear()
        pending.clear()
        metadata.clear()
        activityPrefs.clear()
    }

    companion object {
        @Volatile
        private var instance: WhoofRepository? = null

        fun getInstance(context: SealedLightContext): WhoofRepository =
            instance ?: synchronized(this) {
                instance ?: DefaultWhoofRepository(
                    session = PrivateWhoopServices.getInstance(context),
                    cache = RoomWhoopCache(
                        context.buildDatabase(WhoofDatabase::class.java, WHOOF_DATABASE_NAME),
                    ),
                    metadata = DataStoreSyncMetadata(context.dataStore),
                    activityPrefs = DataStoreActivityPrefs(context.dataStore),
                    pending = RoomPendingActivityStore(
                        context.buildDatabase(PendingDatabase::class.java, PENDING_DATABASE_NAME),
                    ),
                ).also { instance = it }
            }
    }
}
