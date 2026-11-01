package com.whoof.privateapi

import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

class WhoopPrivateServiceTest {
    @Test
    fun provisioningChecksAccountBeforePersistingSession() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPrivateWhoopStore(events = events)
        val client = FakeWhoopPrivateTransport(events)
        service(store, client).provision(provisioningUri())

        assertEquals(listOf("check", "save:none"), events)
        assertEquals(importedSession(), store.current.session)
    }

    @Test
    fun refreshTokenProvisioningRefreshesThenSavesThenChecks() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPrivateWhoopStore(events = events)
        val client = FakeWhoopPrivateTransport(events)

        service(store, client).provision(refreshProvisioningUri())

        assertEquals(listOf("refresh", "save:none", "check"), events)
        assertEquals(listOf("import-refresh-token-synthetic-123"), client.scannedRefreshTokens)
        assertEquals(client.refreshResult, store.current.session)
    }

    @Test
    fun refreshTokenProvisioningFailureSavesNothing() = runBlocking {
        val original = PrivateWhoopState(session = SESSION)
        for (failure in listOf(PrivateWhoopError.Unauthorized, PrivateWhoopError.Rejected)) {
            val store = MemoryPrivateWhoopStore(original)
            val client = FakeWhoopPrivateTransport().apply {
                refreshFailure = PrivateWhoopException(failure)
            }

            assertPrivateError(PrivateWhoopError.Unauthorized) {
                service(store, client).provision(refreshProvisioningUri())
            }

            assertEquals(original, store.current)
            assertEquals(0, store.saveCount)
            assertEquals(0, client.checkCount)
        }
    }

    @Test
    fun refreshTokenProvisioningKeepsRotatedSessionWhenAccountCheckFails() = runBlocking {
        val store = MemoryPrivateWhoopStore()
        val client = FakeWhoopPrivateTransport().apply {
            checkFailures += PrivateWhoopException(PrivateWhoopError.Unavailable)
        }

        assertPrivateError(PrivateWhoopError.Unavailable) {
            service(store, client).provision(refreshProvisioningUri())
        }

        assertEquals(client.refreshResult, store.current.session)
    }

    @Test
    fun invalidImportLeavesPreviouslyStoredSessionUntouched() = runBlocking {
        val original = PrivateWhoopState(session = SESSION)
        val store = MemoryPrivateWhoopStore(original)
        val client = FakeWhoopPrivateTransport()

        assertPrivateError(PrivateWhoopError.InvalidInput) {
            service(store, client).provision("whoof://private-provision?v=1&access_token=invalid")
        }

        assertEquals(original, store.current)
        assertEquals(0, client.checkCount)
    }

    @Test
    fun rejectedProvisionAccountCheckDoesNotPersistImportedSession() = runBlocking {
        val original = PrivateWhoopState(session = SESSION)
        val store = MemoryPrivateWhoopStore(original)
        val client = FakeWhoopPrivateTransport().apply {
            checkFailures += PrivateWhoopException(PrivateWhoopError.Unauthorized)
        }

        assertPrivateError(PrivateWhoopError.Unauthorized) {
            service(store, client).provision(provisioningUri())
        }

        assertEquals(original, store.current)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun directLoginChallengeIsReturnedButNeverPersisted() = runBlocking {
        val store = MemoryPrivateWhoopStore()
        val client = FakeWhoopPrivateTransport().apply {
            beginResult = PrivateWhoopLoginResult.VerificationRequired(
                PrivateWhoopChallenge("EMAIL_OTP", "synthetic-challenge", "user@example.invalid"),
            )
        }

        val result = service(store, client).beginLogin("user@example.invalid", "synthetic-password")

        val verification = assertIs<PrivateWhoopLoginResult.VerificationRequired>(result)
        assertEquals("synthetic-challenge", verification.challenge.session)
        assertNull(store.current.session)
        assertEquals(0, client.checkCount)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun authenticatedDirectLoginChecksAccountBeforeSaving() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPrivateWhoopStore(events = events)
        val client = FakeWhoopPrivateTransport(events).apply {
            beginResult = PrivateWhoopLoginResult.Authenticated(SESSION)
        }

        service(store, client).beginLogin("user@example.invalid", "synthetic-password")

        assertEquals(listOf("begin", "check", "save:none"), events)
        assertEquals(SESSION, store.current.session)
    }

    @Test
    fun draftAcceptsMinimumAndMaximumDurationAndRejectsOutsideRange() = runBlocking {
        draft(NOW.minusSeconds(60), NOW).validate(NOW)
        draft(NOW.minusSeconds(86_400), NOW).validate(NOW)

        assertPrivateError(PrivateWhoopError.InvalidInput) {
            draft(NOW.minusMillis(59_999), NOW).validate(NOW)
        }
        assertPrivateError(PrivateWhoopError.InvalidInput) {
            draft(NOW.minusMillis(86_400_001), NOW).validate(NOW)
        }
    }

    @Test
    fun draftRequiresCompletedActivityAndKnownTimeZone() = runBlocking {
        assertPrivateError(PrivateWhoopError.InvalidInput) {
            draft(NOW.minusSeconds(60), NOW.plusSeconds(1)).validate(NOW)
        }
        assertPrivateError(PrivateWhoopError.InvalidInput) {
            draft(NOW.minusSeconds(60), NOW, timeZone = "Not/A_Zone").validate(NOW)
        }
    }

    @Test
    fun payloadUsesMillisecondUtcInstantsAcrossDaylightSavingTransition() {
        val activity = PrivateActivityDraft(
            id = UUID.randomUUID().toString(),
            sportId = 0,
            sportName = "Running",
            startEpochMillis = Instant.parse("2024-03-31T00:30:00.123Z").toEpochMilli(),
            endEpochMillis = Instant.parse("2024-03-31T01:30:00.456Z").toEpochMilli(),
            timeZone = "Europe/London",
        )

        val payload = activity.payload()

        assertEquals("2024-03-31T00:30:00.123Z", payload.startTime)
        assertEquals("2024-03-31T01:30:00.456Z", payload.endTime)
        assertEquals(0, payload.sportId)
        assertFalse(payload.gpsEnabled)
    }

    @Test
    fun overlapAndTooShortCreateErrorsMarkTheDraftRejected() = runBlocking {
        for (failure in listOf(PrivateWhoopError.Overlap, PrivateWhoopError.TooShort)) {
            val (service, client, draft) = preparedService()
            client.createFailure = PrivateWhoopException(failure)

            assertPrivateError(failure) { service.submitActivity(draft.id, confirmed = true) }

            assertEquals(PrivateActivityStatus.Rejected, service.state().draft?.status)
            assertEquals(1, client.createCount)
        }
    }

    @Test
    fun deleteRequiresConfirmationAndCanonicalUuid() = runBlocking {
        val id = UUID.randomUUID().toString()
        val client = FakeWhoopPrivateTransport()
        val service = service(MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION)), client)

        assertPrivateError(PrivateWhoopError.ConfirmationRequired) { service.deleteActivity(id, confirmed = false) }
        assertPrivateError(PrivateWhoopError.InvalidInput) { service.deleteActivity(id.uppercase(), confirmed = true) }

        assertEquals(0, client.deletedIds.size)
        assertEquals(0, client.checkCount)
    }

    @Test
    fun deleteRefreshesExpiredSessionFirstAndSendsExactlyOneDelete() = runBlocking {
        val events = mutableListOf<String>()
        val id = UUID.randomUUID().toString()
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = session(expiry = NOW.epochSecond)), events)
        val client = FakeWhoopPrivateTransport(events)

        service(store, client).deleteActivity(id, confirmed = true)

        assertEquals(listOf("refresh", "save:none", "check", "delete"), events)
        assertEquals(listOf(id), client.deletedIds)
        assertEquals(client.refreshResult, store.current.session)
    }

    @Test
    fun deleteFailuresAreNeverRetried() = runBlocking {
        for (failure in listOf(PrivateWhoopError.Unauthorized, PrivateWhoopError.Unavailable)) {
            val client = FakeWhoopPrivateTransport().apply { deleteFailure = PrivateWhoopException(failure) }
            val service = service(MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION)), client)

            assertPrivateError(failure) { service.deleteActivity(UUID.randomUUID().toString(), confirmed = true) }

            assertEquals(1, client.deletedIds.size)
            assertEquals(0, client.refreshCount)
        }
    }

    @Test
    fun deleteTreatsNotFoundAsSuccessAndClearsMatchingSubmittedDraft() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        val submitted = service.submitActivity(draft.id, confirmed = true)
        client.deleteFailure = PrivateWhoopException(PrivateWhoopError.NotFound)

        service.deleteActivity(submitted.activityId!!, confirmed = true)

        assertEquals(listOf(submitted.activityId), client.deletedIds)
        assertNull(store.current.draft)
    }

    @Test
    fun deleteOfAnotherActivityKeepsTheSubmittedDraft() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        val submitted = service.submitActivity(draft.id, confirmed = true)

        service.deleteActivity(UUID.randomUUID().toString(), confirmed = true)

        assertEquals(submitted, store.current.draft)
        assertEquals(1, client.deletedIds.size)
    }

    @Test
    fun confirmedFalseDoesNotSubmitActivity() = runBlocking {
        val (service, client, draft) = preparedService()

        assertPrivateError(PrivateWhoopError.ConfirmationRequired) {
            service.submitActivity(draft.id, confirmed = false)
        }

        assertEquals(0, client.createCount)
        assertEquals(PrivateActivityStatus.Prepared, service.state().draft?.status)
    }

    @Test
    fun submitRefreshesAndPersistsBeforeAccountCheckUncertainMarkerAndPost() = runBlocking {
        val events = mutableListOf<String>()
        val expired = session(expiry = NOW.epochSecond)
        val replacement = session("replacement", NOW.plusSeconds(3_600).epochSecond)
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = expired), events)
        val client = FakeWhoopPrivateTransport(events).apply { refreshResult = replacement }
        val service = service(store, client)
        val draft = service.prepareActivity(RUNNING, NOW.minusSeconds(600), NOW, ZoneId.of("UTC"))
        events.clear()

        val submitted = service.submitActivity(draft.id, confirmed = true)

        assertEquals(PrivateActivityStatus.Submitted, submitted.status)
        assertEquals(listOf("refresh", "save:Prepared", "check", "save:Uncertain", "post", "save:Submitted"), events)
        assertEquals(replacement, store.current.session)
    }

    @Test
    fun freshSessionAccountGet401RefreshesAndRetriesOnce() = runBlocking {
        val events = mutableListOf<String>()
        val replacement = session("replacement", NOW.plusSeconds(3_600).epochSecond)
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION), events)
        val client = FakeWhoopPrivateTransport(events).apply {
            checkFailures += PrivateWhoopException(PrivateWhoopError.Unauthorized)
            refreshResult = replacement
        }

        service(store, client).checkConnection()

        assertEquals(listOf("check", "refresh", "save:none", "check"), events)
        assertEquals(1, client.refreshCount)
        assertEquals(2, client.checkCount)
        assertEquals(replacement, store.current.session)
    }

    @Test
    fun proactiveRefreshFollowedByGet401DoesNotRefreshAgain() = runBlocking {
        val events = mutableListOf<String>()
        val expired = session(expiry = NOW.epochSecond)
        val replacement = session("replacement", NOW.plusSeconds(3_600).epochSecond)
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = expired), events)
        val client = FakeWhoopPrivateTransport(events).apply {
            refreshResult = replacement
            checkFailures += PrivateWhoopException(PrivateWhoopError.Unauthorized)
        }

        assertPrivateError(PrivateWhoopError.ReconnectRequired) {
            service(store, client).checkConnection()
        }

        assertEquals(listOf("refresh", "save:none", "check"), events)
        assertEquals(1, client.refreshCount)
        assertEquals(1, client.checkCount)
    }

    @Test
    fun submitRefreshesOnceOnAccountGet401ButNeverRefreshesOrReplaysPost401() = runBlocking {
        val events = mutableListOf<String>()
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION), events)
        val client = FakeWhoopPrivateTransport(events).apply {
            checkFailures += PrivateWhoopException(PrivateWhoopError.Unauthorized)
            refreshResult = session("replacement", NOW.plusSeconds(3_600).epochSecond)
            createFailure = PrivateWhoopException(PrivateWhoopError.Unauthorized)
        }
        val service = service(store, client)
        val draft = service.prepareActivity(RUNNING, NOW.minusSeconds(600), NOW, ZoneId.of("UTC"))
        events.clear()

        assertPrivateError(PrivateWhoopError.Unauthorized) {
            service.submitActivity(draft.id, confirmed = true)
        }

        assertEquals(listOf("check", "refresh", "save:Prepared", "check", "save:Uncertain", "post", "save:Rejected"), events)
        assertEquals(1, client.refreshCount)
        assertEquals(1, client.createCount)
        assertEquals(PrivateActivityStatus.Rejected, store.current.draft?.status)
    }

    @Test
    fun post401DoesNotRefreshOrReplayActivityWrite() = runBlocking {
        val (service, client, draft) = preparedService()
        client.createFailure = PrivateWhoopException(PrivateWhoopError.Unauthorized)

        assertPrivateError(PrivateWhoopError.Unauthorized) { service.submitActivity(draft.id, true) }

        assertEquals(0, client.refreshCount)
        assertEquals(1, client.createCount)
    }

    @Test
    fun failureSavingUncertainMarkerPreventsPost() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        store.failSaveWhen = { it.draft?.status == PrivateActivityStatus.Uncertain }

        assertPrivateError(PrivateWhoopError.StorageFailed) {
            service.submitActivity(draft.id, confirmed = true)
        }

        assertEquals(0, client.createCount)
        assertEquals(PrivateActivityStatus.Prepared, store.current.draft?.status)
    }

    @Test
    fun postTimeoutLeavesDurableUncertainMarkerAndIsNeverReplayed() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        client.createFailure = SocketTimeoutException("synthetic timeout")

        assertPrivateError(PrivateWhoopError.SubmissionUncertain) {
            service.submitActivity(draft.id, confirmed = true)
        }
        assertPrivateError(PrivateWhoopError.SubmissionUncertain) {
            service.submitActivity(draft.id, confirmed = true)
        }

        assertEquals(1, client.createCount)
        assertEquals(PrivateActivityStatus.Uncertain, store.current.draft?.status)
    }

    @Test
    fun cancellationAfterPostLeavesDurableUncertainMarker() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        client.createFailure = kotlinx.coroutines.CancellationException("synthetic cancellation")

        val error = assertFailsWith<kotlinx.coroutines.CancellationException> {
            withContext(NonCancellable) { service.submitActivity(draft.id, confirmed = true) }
        }

        assertEquals("synthetic cancellation", error.message)
        assertEquals(1, client.createCount)
        assertEquals(PrivateActivityStatus.Uncertain, store.current.draft?.status)
    }

    @Test
    fun malformedReceiptLeavesDurableUncertainMarker() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        client.receipt = "not-a-canonical-uuid"

        assertPrivateError(PrivateWhoopError.SubmissionUncertain) {
            service.submitActivity(draft.id, confirmed = true)
        }

        assertEquals(1, client.createCount)
        assertEquals(PrivateActivityStatus.Uncertain, store.current.draft?.status)
    }

    @Test
    fun receiptSaveFailureLeavesPreviouslyPersistedUncertainMarker() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        store.failSaveWhen = { it.draft?.status == PrivateActivityStatus.Submitted }

        assertPrivateError(PrivateWhoopError.SubmissionUncertain) {
            service.submitActivity(draft.id, confirmed = true)
        }

        assertEquals(1, client.createCount)
        assertEquals(PrivateActivityStatus.Uncertain, store.current.draft?.status)
    }

    @Test
    fun definiteRejectionIsRecordedAndNeverReplayed() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        client.createFailure = PrivateWhoopException(PrivateWhoopError.Rejected)

        assertPrivateError(PrivateWhoopError.Rejected) { service.submitActivity(draft.id, true) }
        assertPrivateError(PrivateWhoopError.AlreadyAttempted) { service.submitActivity(draft.id, true) }

        assertEquals(1, client.createCount)
        assertEquals(PrivateActivityStatus.Rejected, store.current.draft?.status)
    }

    @Test
    fun twoServiceInstancesSubmittingSameDraftIssueOnlyOnePost() = runBlocking {
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION))
        val enteredPost = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        val client = FakeWhoopPrivateTransport().apply {
            beforeCreate = {
                enteredPost.complete(Unit)
                releasePost.await()
            }
        }
        val firstService = service(store, client)
        val secondService = service(store, client)
        val draft = firstService.prepareActivity(
            RUNNING, NOW.minusSeconds(600), NOW, ZoneId.of("UTC"),
        )

        val first = async { firstService.submitActivity(draft.id, confirmed = true) }
        enteredPost.await()
        val second = async {
            try {
                secondService.submitActivity(draft.id, confirmed = true)
                null
            } catch (error: PrivateWhoopException) {
                error.reason
            }
        }
        releasePost.complete(Unit)

        assertEquals(PrivateActivityStatus.Submitted, first.await().status)
        assertEquals(PrivateWhoopError.AlreadyAttempted, second.await())
        assertEquals(1, client.createCount)
    }

    @Test
    fun submittedDraftIsNeverReplayed() = runBlocking {
        val (service, client, draft) = preparedService()

        service.submitActivity(draft.id, confirmed = true)
        assertPrivateError(PrivateWhoopError.AlreadyAttempted) { service.submitActivity(draft.id, true) }

        assertEquals(1, client.createCount)
    }

    @Test
    fun uncertainDraftBlocksAnotherAttemptUntilExplicitDiscard() = runBlocking {
        val (service, client, draft, store) = preparedServiceWithStore()
        client.createFailure = SocketTimeoutException("synthetic timeout")
        assertPrivateError(PrivateWhoopError.SubmissionUncertain) { service.submitActivity(draft.id, true) }

        assertPrivateError(PrivateWhoopError.SubmissionUncertain) {
            service.prepareActivity(CYCLING, NOW.minusSeconds(300), NOW, ZoneId.of("UTC"))
        }
        assertPrivateError(PrivateWhoopError.ConfirmationRequired) { service.discardDraft(confirmed = false) }
        service.discardDraft(confirmed = true)
        client.createFailure = null

        val next = service.prepareActivity(CYCLING, NOW.minusSeconds(300), NOW, ZoneId.of("UTC"))
        assertEquals(PrivateActivityStatus.Prepared, next.status)
        assertNull(store.current.draft?.activityId)
        assertEquals(1, client.createCount)
    }

    @Test
    fun reconnectClearsPreparedDraftAndPreservesUncertainMarker() = runBlocking {
        val (service, _, prepared) = preparedService()
        service.provision(provisioningUri())
        assertEquals(importedSession(), service.state().session)
        assertNull(service.state().draft)

        val uncertainStore = MemoryPrivateWhoopStore(
            PrivateWhoopState(session = SESSION, draft = prepared.copy(status = PrivateActivityStatus.Uncertain)),
        )
        val uncertainService = service(uncertainStore, FakeWhoopPrivateTransport())
        uncertainService.disconnect()
        assertNull(uncertainService.state().session)
        assertEquals(PrivateActivityStatus.Uncertain, uncertainService.state().draft?.status)
        uncertainService.provision(provisioningUri())
        assertEquals(PrivateActivityStatus.Uncertain, uncertainService.state().draft?.status)
        assertEquals(PrivateActivityStatus.Prepared, prepared.status)
    }

    @Test
    fun disconnectOnlyClearsPrivateSessionWithoutRemoteLogoutOrDeveloperOAuthCalls() = runBlocking {
        val (service, client) = preparedService()

        service.disconnect()

        assertNull(service.state().session)
        assertEquals(0, client.refreshCount)
        assertEquals(0, client.checkCount)
        assertEquals(0, client.createCount)
    }

    private suspend fun preparedService(): Triple<WhoopPrivateService, FakeWhoopPrivateTransport, PrivateActivityDraft> {
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION))
        val client = FakeWhoopPrivateTransport()
        val service = service(store, client)
        val draft = service.prepareActivity(RUNNING, NOW.minusSeconds(600), NOW, ZoneId.of("UTC"))
        return Triple(service, client, draft)
    }

    private suspend fun preparedServiceWithStore(): PreparedService {
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION))
        val client = FakeWhoopPrivateTransport()
        val service = service(store, client)
        val draft = service.prepareActivity(RUNNING, NOW.minusSeconds(600), NOW, ZoneId.of("UTC"))
        return PreparedService(service, client, draft, store)
    }

    @Test
    fun accessTokenReturnsTheStoredTokenWithoutRefreshingWhenFresh() = runBlocking {
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION))
        val client = FakeWhoopPrivateTransport()

        assertEquals(SESSION.accessToken, service(store, client).accessToken())

        assertEquals(0, client.refreshCount)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun accessTokenRefreshesAndPersistsWhenWithinSixtySecondsOfExpiry() = runBlocking {
        val events = mutableListOf<String>()
        val nearExpiry = session("original", NOW.plusSeconds(59).epochSecond)
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = nearExpiry), events)
        val client = FakeWhoopPrivateTransport(events)

        val token = service(store, client).accessToken()

        assertEquals(client.refreshResult.accessToken, token)
        assertEquals(client.refreshResult, store.current.session)
        assertEquals(listOf("refresh", "save:none"), events)
    }

    @Test
    fun accessTokenRefreshesOnceForConcurrentCallers() = runBlocking {
        val expired = session("original", NOW.minusSeconds(10).epochSecond)
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = expired))
        val client = FakeWhoopPrivateTransport()
        val service = service(store, client)

        val tokens = (1..5).map { async { service.accessToken() } }.map { it.await() }

        assertEquals(1, client.refreshCount)
        assertEquals(List(5) { client.refreshResult.accessToken }, tokens)
    }

    @Test
    fun forcedRefreshRotatesOnceEvenWhenTwoGetsReportTheSameRejectedToken() = runBlocking {
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION))
        val client = FakeWhoopPrivateTransport()
        val service = service(store, client)

        val first = async { service.accessToken(forceRefresh = true, rejectedToken = SESSION.accessToken) }
        val second = async { service.accessToken(forceRefresh = true, rejectedToken = SESSION.accessToken) }

        assertEquals(client.refreshResult.accessToken, first.await())
        assertEquals(client.refreshResult.accessToken, second.await())
        assertEquals(1, client.refreshCount)
        assertEquals(client.refreshResult, store.current.session)
    }

    @Test
    fun forcedRefreshWithoutARejectedTokenAlwaysRefreshes() = runBlocking {
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = SESSION))
        val client = FakeWhoopPrivateTransport()

        service(store, client).accessToken(forceRefresh = true)

        assertEquals(1, client.refreshCount)
    }

    @Test
    fun accessTokenMapsRejectedRefreshToReconnectRequiredAndKeepsTheStoredSession() = runBlocking {
        for (failure in listOf(PrivateWhoopError.Unauthorized, PrivateWhoopError.Rejected)) {
            val expired = session("original", NOW.minusSeconds(10).epochSecond)
            val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = expired))
            val client = FakeWhoopPrivateTransport().apply { refreshFailure = PrivateWhoopException(failure) }

            assertPrivateError(PrivateWhoopError.ReconnectRequired) { service(store, client).accessToken() }
            assertPrivateError(PrivateWhoopError.ReconnectRequired) {
                service(store, client).accessToken(forceRefresh = true)
            }

            assertEquals(expired, store.current.session)
            assertEquals(0, store.saveCount)
        }
    }

    @Test
    fun accessTokenPropagatesTransientRefreshFailuresWithoutChangingTheSession() = runBlocking {
        val expired = session("original", NOW.minusSeconds(10).epochSecond)
        val store = MemoryPrivateWhoopStore(PrivateWhoopState(session = expired))
        val client = FakeWhoopPrivateTransport().apply {
            refreshFailure = PrivateWhoopException(PrivateWhoopError.Unavailable)
        }

        assertPrivateError(PrivateWhoopError.Unavailable) { service(store, client).accessToken() }

        assertEquals(expired, store.current.session)
    }

    @Test
    fun accessTokenWithoutASessionRequiresReconnect() = runBlocking {
        val client = FakeWhoopPrivateTransport()

        assertPrivateError(PrivateWhoopError.ReconnectRequired) {
            service(MemoryPrivateWhoopStore(), client).accessToken()
        }

        assertEquals(0, client.refreshCount)
    }

    private fun service(store: PrivateWhoopStore, client: FakeWhoopPrivateTransport) =
        WhoopPrivateService(store, client) { NOW }

    private data class PreparedService(
        val service: WhoopPrivateService,
        val client: FakeWhoopPrivateTransport,
        val draft: PrivateActivityDraft,
        val store: MemoryPrivateWhoopStore,
    )

    private fun draft(
        start: Instant,
        end: Instant,
        timeZone: String = "UTC",
    ) = PrivateActivityDraft(UUID.randomUUID().toString(), 0, "Running",
        start.toEpochMilli(), end.toEpochMilli(), timeZone)

    private suspend fun assertPrivateError(expected: PrivateWhoopError, block: suspend () -> Unit) {
        val error = try {
            block()
            error("Expected private API failure $expected")
        } catch (failure: PrivateWhoopException) {
            failure
        }
        assertEquals(expected, error.reason)
    }

    private companion object {
        val RUNNING = PrivateWhoopSports.byId(0)!!
        val CYCLING = PrivateWhoopSports.byId(1)!!
        val NOW: Instant = Instant.parse("2025-01-01T12:00:00Z")
        val SESSION = session("original", NOW.plusSeconds(3_600).epochSecond)
    }
}

private class MemoryPrivateWhoopStore(
    initial: PrivateWhoopState = PrivateWhoopState(),
    private val events: MutableList<String> = mutableListOf(),
) : PrivateWhoopStore {
    var current: PrivateWhoopState = initial
        private set
    var saveCount: Int = 0
        private set
    var failSaveWhen: ((PrivateWhoopState) -> Boolean)? = null

    override suspend fun load(): PrivateWhoopState = current

    override suspend fun save(state: PrivateWhoopState) {
        saveCount++
        events += "save:${state.draft?.status?.name ?: "none"}"
        if (failSaveWhen?.invoke(state) == true) {
            throw PrivateWhoopException(PrivateWhoopError.StorageFailed)
        }
        current = state
    }

    override suspend fun clear() {
        current = PrivateWhoopState()
    }
}

private class FakeWhoopPrivateTransport(
    private val events: MutableList<String> = mutableListOf(),
) : WhoopPrivateTransport {
    var beginResult: PrivateWhoopLoginResult = PrivateWhoopLoginResult.VerificationRequired(
        PrivateWhoopChallenge("EMAIL_OTP", "synthetic-challenge", "user@example.invalid"),
    )
    var completeResult: PrivateWhoopLoginResult = beginResult
    var refreshResult: PrivateWhoopSession = session("replacement", NOW_EPOCH + 3_600)
    var refreshFailure: Throwable? = null
    var createFailure: Throwable? = null
    var deleteFailure: Throwable? = null
    val deletedIds = mutableListOf<String>()
    var receipt: String = UUID.randomUUID().toString()
    var beforeCreate: (suspend () -> Unit)? = null
    val checkFailures = mutableListOf<Throwable>()
    val scannedRefreshTokens = mutableListOf<String>()
    var checkCount = 0
        private set
    var refreshCount = 0
        private set
    var createCount = 0
        private set

    override suspend fun beginLogin(email: String, password: String): PrivateWhoopLoginResult {
        events += "begin"
        return beginResult
    }

    override suspend fun completeLogin(challenge: PrivateWhoopChallenge, code: String): PrivateWhoopLoginResult {
        events += "complete"
        return completeResult
    }

    override suspend fun refresh(session: PrivateWhoopSession): PrivateWhoopSession {
        events += "refresh"
        refreshCount++
        refreshFailure?.let { throw it }
        return refreshResult
    }

    override suspend fun refreshFromToken(refreshToken: String): PrivateWhoopSession {
        events += "refresh"
        refreshCount++
        scannedRefreshTokens += refreshToken
        refreshFailure?.let { throw it }
        return refreshResult
    }

    override suspend fun checkAccount(session: PrivateWhoopSession) {
        events += "check"
        checkCount++
        if (checkFailures.isNotEmpty()) throw checkFailures.removeAt(0)
    }

    override suspend fun createActivity(
        session: PrivateWhoopSession,
        payload: PrivateActivityPayload,
        timeZone: String,
    ): String {
        events += "post"
        createCount++
        beforeCreate?.invoke()
        createFailure?.let { throw it }
        return receipt
    }

    override suspend fun deleteActivity(session: PrivateWhoopSession, activityId: String) {
        events += "delete"
        deletedIds += activityId
        deleteFailure?.let { throw it }
    }
}

private fun session(label: String = "original", expiry: Long): PrivateWhoopSession =
    PrivateWhoopSession("$label-access-token-synthetic-123", "$label-refresh-token-synthetic-123", expiry)

private const val NOW_EPOCH = 1_735_732_800L

private fun provisioningUri(
    access: String = "import-access-token-synthetic-123",
    refresh: String = "import-refresh-token-synthetic-123",
    expiry: Long = NOW_EPOCH + 3_600,
): String = "whoof://private-provision?v=1&access_token=$access&refresh_token=$refresh&expires_at=$expiry"

private fun importedSession() = session(
    label = "import",
    expiry = NOW_EPOCH + 3_600,
)

private fun refreshProvisioningUri(refresh: String = "import-refresh-token-synthetic-123") =
    "whoof://private-provision?v=2&refresh_token=$refresh"
