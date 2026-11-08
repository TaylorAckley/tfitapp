package com.tfitapp.application.profile

import com.tfitapp.application.settings.InstantIsoSerializer
import com.tfitapp.domain.nutrition.BodyProfile
import com.tfitapp.domain.nutrition.GoalSettings
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant

/** Who wrote the most recent snapshot — the app's own Settings screen, or Claude via MCP. */
@Serializable
public enum class ProfileSource { APP, CLAUDE }

/**
 * The subset of design basis §3 that is mirrored to Supabase (design basis §2
 * exception, recorded 2026-09-20): body stats, goal phase, and the training
 * schedule. Everything else in [com.tfitapp.application.settings.UserSettings]
 * stays local-only.
 */
@Serializable
public data class SyncedProfile(
    val body: BodyProfile,
    val goal: GoalSettings,
    val trainingDays: Set<DayOfWeek>,
    val timeBudgetMinutes: Int,
    @Serializable(with = InstantIsoSerializer::class)
    val effectiveAt: Instant,
    val source: ProfileSource,
    val note: String? = null,
)

/** Push or pull failed to reach Supabase — never an exception, same shape as [com.tfitapp.application.judgement.JudgementResult]. */
public sealed interface SyncResult {
    public data object Ok : SyncResult
    public data class Unavailable(val reason: String) : SyncResult
}

/**
 * Port implemented by `:infrastructure:supabase`. Supabase is authoritative for
 * [SyncedProfile] when reachable; the phone falls back to its last-synced local
 * copy otherwise (design basis §2 exception) — the app never blocks on this.
 */
public interface ProfileSyncPort {
    /** Inserts a new snapshot. History is append-only; never call this for an unchanged profile. */
    public suspend fun push(profile: SyncedProfile): SyncResult

    /** The latest snapshot for this user, or null if none exists yet or Supabase is unreachable. */
    public suspend fun pull(): SyncedProfile?

    /** Exchanges email/password for a Supabase session, storing the tokens. True on success. */
    public suspend fun signIn(email: String, password: String): Boolean
}
