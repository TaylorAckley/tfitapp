package com.tfitapp.application.profile

import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.settings.UserSettings
import com.tfitapp.domain.nutrition.BodyProfile
import com.tfitapp.domain.nutrition.GoalPhase
import com.tfitapp.domain.nutrition.GoalSettings
import com.tfitapp.domain.nutrition.Sex
import com.tfitapp.domain.time.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncProfileTest {

    private class InMemorySettings(initial: UserSettings = UserSettings()) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UserSettings> get() = state
        override suspend fun current(): UserSettings = state.value
        override suspend fun update(transform: (UserSettings) -> UserSettings) { state.value = transform(state.value) }
    }

    private class FakeSync(var pulled: SyncedProfile? = null, var pushResult: SyncResult = SyncResult.Ok) : ProfileSyncPort {
        val pushed = mutableListOf<SyncedProfile>()
        override suspend fun push(profile: SyncedProfile): SyncResult { pushed += profile; return pushResult }
        override suspend fun pull(): SyncedProfile? = pulled
        override suspend fun signIn(email: String, password: String): Boolean = true
    }

    private val now = Instant.parse("2026-09-20T10:00:00Z")
    private val clock = WallClock { now }
    private val body = BodyProfile(Sex.MALE, 34, 180.0, 82.5, 1.4)
    private val trainingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
    private val settingsWithBody = UserSettings(body = body, trainingDays = trainingDays, timeBudgetMinutes = 45)

    // --- SyncProfileToCloud ---

    @Test
    fun `no body profile yet means nothing to push`() = runTest {
        val sync = FakeSync()
        val result = SyncProfileToCloud(InMemorySettings(), sync, clock)()
        assertEquals(SyncResult.Ok, result)
        assertTrue(sync.pushed.isEmpty())
    }

    @Test
    fun `pushes and records profileSyncedAt when there is no prior remote snapshot`() = runTest {
        val settings = InMemorySettings(settingsWithBody)
        val sync = FakeSync(pulled = null)
        val result = SyncProfileToCloud(settings, sync, clock)()

        assertEquals(SyncResult.Ok, result)
        assertEquals(1, sync.pushed.size)
        assertEquals(body, sync.pushed.single().body)
        assertEquals(now, settings.current().profileSyncedAt)
    }

    @Test
    fun `an unchanged profile is not pushed again`() = runTest {
        val settings = InMemorySettings(settingsWithBody)
        val matching = SyncedProfile(body, GoalSettings(), trainingDays, 45, now, ProfileSource.APP)
        val sync = FakeSync(pulled = matching)

        val result = SyncProfileToCloud(settings, sync, clock)()

        assertEquals(SyncResult.Ok, result)
        assertTrue(sync.pushed.isEmpty(), "identical to the last known remote snapshot — retrying must not spam history")
    }

    @Test
    fun `a changed weight is pushed even if a remote snapshot already exists`() = runTest {
        val settings = InMemorySettings(settingsWithBody)
        val stale = SyncedProfile(body.copy(weightKg = 80.0), GoalSettings(), trainingDays, 45, now.minusSeconds(3600), ProfileSource.APP)
        val sync = FakeSync(pulled = stale)

        SyncProfileToCloud(settings, sync, clock)()

        assertEquals(1, sync.pushed.size)
        assertEquals(82.5, sync.pushed.single().body.weightKg)
    }

    @Test
    fun `a push failure leaves profileSyncedAt untouched`() = runTest {
        val settings = InMemorySettings(settingsWithBody)
        val sync = FakeSync(pushResult = SyncResult.Unavailable("offline"))

        val result = SyncProfileToCloud(settings, sync, clock)()

        assertIs<SyncResult.Unavailable>(result)
        assertNull(settings.current().profileSyncedAt)
    }

    // --- PullProfileFromCloud ---

    @Test
    fun `no remote snapshot is unavailable and changes nothing`() = runTest {
        val settings = InMemorySettings(settingsWithBody)
        val result = PullProfileFromCloud(settings, FakeSync(pulled = null))()
        assertIs<SyncResult.Unavailable>(result)
        assertEquals(settingsWithBody, settings.current())
    }

    @Test
    fun `a newer remote snapshot is merged into local settings`() = runTest {
        val local = settingsWithBody.copy(profileSyncedAt = now.minusSeconds(3600))
        val settings = InMemorySettings(local)
        val remote = SyncedProfile(
            body.copy(weightKg = 81.0), GoalSettings(GoalPhase.CUT), trainingDays, 30, now, ProfileSource.CLAUDE,
        )

        val result = PullProfileFromCloud(settings, FakeSync(pulled = remote))()

        assertEquals(SyncResult.Ok, result)
        assertEquals(81.0, settings.current().body!!.weightKg)
        assertEquals(GoalPhase.CUT, settings.current().goal.phase)
        assertEquals(30, settings.current().timeBudgetMinutes)
        assertEquals(now, settings.current().profileSyncedAt)
    }

    @Test
    fun `a remote snapshot no newer than the last sync is a no-op`() = runTest {
        val local = settingsWithBody.copy(profileSyncedAt = now)
        val settings = InMemorySettings(local)
        val stale = SyncedProfile(body.copy(weightKg = 99.0), GoalSettings(), trainingDays, 45, now.minusSeconds(60), ProfileSource.CLAUDE)

        PullProfileFromCloud(settings, FakeSync(pulled = stale))()

        assertEquals(local, settings.current(), "a stale remote snapshot must not overwrite a more recent local edit")
    }

    @Test
    fun `the first pull always applies, even though local profileSyncedAt is null`() = runTest {
        val settings = InMemorySettings(settingsWithBody)
        val remote = SyncedProfile(body, GoalSettings(GoalPhase.LEAN_GAIN), trainingDays, 45, now, ProfileSource.CLAUDE)

        PullProfileFromCloud(settings, FakeSync(pulled = remote))()

        assertEquals(GoalPhase.LEAN_GAIN, settings.current().goal.phase)
    }
}
