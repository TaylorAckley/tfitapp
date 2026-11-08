package com.tfitapp.application.profile

import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.settings.UserSettings
import com.tfitapp.domain.time.WallClock
import javax.inject.Inject

private fun UserSettings.toSyncedProfile(source: ProfileSource, at: java.time.Instant): SyncedProfile? {
    val body = body ?: return null
    return SyncedProfile(body, goal, trainingDays, timeBudgetMinutes, at, source)
}

/**
 * Pushes the local body/goal/schedule to Supabase, only when it actually changed —
 * retrying an unchanged edit must not spam the append-only history table.
 * No body profile yet (onboarding incomplete) → nothing to push.
 */
public class SyncProfileToCloud @Inject constructor(
    private val settings: SettingsRepository,
    private val sync: ProfileSyncPort,
    private val clock: WallClock,
) {
    public suspend operator fun invoke(): SyncResult {
        val current = settings.current()
        val candidate = current.toSyncedProfile(ProfileSource.APP, clock.now()) ?: return SyncResult.Ok
        val last = runCatching { sync.pull() }.getOrNull()
        if (last != null && !last.differsFrom(candidate)) return SyncResult.Ok

        val result = sync.push(candidate)
        if (result is SyncResult.Ok) settings.update { it.copy(profileSyncedAt = candidate.effectiveAt) }
        return result
    }

    private fun SyncedProfile.differsFrom(other: SyncedProfile): Boolean =
        body != other.body || goal != other.goal || trainingDays != other.trainingDays || timeBudgetMinutes != other.timeBudgetMinutes
}

/**
 * Pulls the latest Supabase snapshot and, only if it is newer than what this phone
 * last synced, merges it into local settings. Last-writer-wins by [SyncedProfile.effectiveAt] —
 * proportionate for one person editing from at most two places that are essentially
 * never concurrent (design basis §2 exception).
 */
public class PullProfileFromCloud @Inject constructor(
    private val settings: SettingsRepository,
    private val sync: ProfileSyncPort,
) {
    public suspend operator fun invoke(): SyncResult {
        val remote = sync.pull() ?: return SyncResult.Unavailable("No snapshot, or Supabase unreachable")
        val local = settings.current()
        if (local.profileSyncedAt != null && !remote.effectiveAt.isAfter(local.profileSyncedAt)) return SyncResult.Ok

        settings.update {
            it.copy(
                body = remote.body,
                goal = remote.goal,
                trainingDays = remote.trainingDays,
                timeBudgetMinutes = remote.timeBudgetMinutes,
                profileSyncedAt = remote.effectiveAt,
            )
        }
        return SyncResult.Ok
    }
}
