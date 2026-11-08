package com.tfitapp.infrastructure.supabase

import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import com.tfitapp.application.profile.ProfileSource
import com.tfitapp.application.profile.ProfileSyncPort
import com.tfitapp.application.profile.SyncResult
import com.tfitapp.application.profile.SyncedProfile
import com.tfitapp.domain.nutrition.BodyProfile
import com.tfitapp.domain.nutrition.GoalPhase
import com.tfitapp.domain.nutrition.GoalSettings
import com.tfitapp.domain.nutrition.Sex
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant

/**
 * The only code that knows Supabase's URL, PostgREST/GoTrue shapes, or how the
 * `profile_snapshot` table's columns map to [SyncedProfile]. Design basis §2
 * exception: Supabase is authoritative for this data when reachable; every
 * failure here becomes [SyncResult.Unavailable] or `null` so the phone falls
 * back to its last-synced local copy rather than blocking (design basis §2).
 */
public class SupabaseProfileClient(
    engine: HttpClientEngine,
    private val secrets: SecretStore,
) : ProfileSyncPort {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine) { expectSuccess = false }

    override suspend fun push(profile: SyncedProfile): SyncResult {
        val creds = credentials() ?: return SyncResult.Unavailable("Supabase not configured")
        val body = json.encodeToString(JsonObject.serializer(), requestBody(profile))

        val response = runCatching { post(creds, body) }
            .getOrElse { e -> return SyncResult.Unavailable("Network: ${e.message ?: e::class.simpleName}") }

        if (response.status.value == 401 && refresh(creds)) {
            val retryCreds = credentials() ?: return SyncResult.Unavailable("Supabase not configured")
            val retried = runCatching { post(retryCreds, body) }
                .getOrElse { e -> return SyncResult.Unavailable("Network: ${e.message ?: e::class.simpleName}") }
            return if (retried.status.value in 200..299) SyncResult.Ok else SyncResult.Unavailable("Supabase HTTP ${retried.status.value}")
        }
        return if (response.status.value in 200..299) SyncResult.Ok else SyncResult.Unavailable("Supabase HTTP ${response.status.value}")
    }

    override suspend fun pull(): SyncedProfile? {
        val creds = credentials() ?: return null
        var response = runCatching { get(creds) }.getOrNull() ?: return null
        if (response.status.value == 401 && refresh(creds)) {
            val retryCreds = credentials() ?: return null
            response = runCatching { get(retryCreds) }.getOrNull() ?: return null
        }
        if (response.status.value !in 200..299) return null
        return runCatching { parse(response.bodyAsText()) }.getOrNull()
    }

    private suspend fun post(creds: Credentials, body: String): HttpResponse = http.post {
        url("${creds.baseUrl}/rest/v1/profile_snapshot")
        header("apikey", creds.anonKey)
        header("Authorization", "Bearer ${creds.accessToken}")
        header("Prefer", "return=minimal")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun get(creds: Credentials): HttpResponse = http.get {
        url("${creds.baseUrl}/rest/v1/current_profile?select=*")
        header("apikey", creds.anonKey)
        header("Authorization", "Bearer ${creds.accessToken}")
    }

    /** One refresh attempt via GoTrue, storing the rotated tokens. True if a new access token is now stored. */
    private suspend fun refresh(creds: Credentials): Boolean =
        exchangeForSession(creds.baseUrl, creds.anonKey, "refresh_token", buildJsonObject { put("refresh_token", creds.refreshToken) })

    override suspend fun signIn(email: String, password: String): Boolean {
        val baseUrl = secrets.get(SecretKey.SUPABASE_URL)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return false
        val anonKey = secrets.get(SecretKey.SUPABASE_ANON_KEY)?.takeIf { it.isNotBlank() } ?: return false
        return exchangeForSession(baseUrl, anonKey, "password", buildJsonObject { put("email", email); put("password", password) })
    }

    /** POSTs to GoTrue's token endpoint and stores whatever session it returns. True on success. */
    private suspend fun exchangeForSession(baseUrl: String, anonKey: String, grantType: String, body: JsonObject): Boolean {
        val response = runCatching {
            http.post {
                url("$baseUrl/auth/v1/token?grant_type=$grantType")
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), body))
            }
        }.getOrNull() ?: return false
        if (response.status.value !in 200..299) return false

        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return false
        val accessToken = (root["access_token"] as? JsonPrimitive)?.contentOrNull ?: return false
        val refreshToken = (root["refresh_token"] as? JsonPrimitive)?.contentOrNull ?: return false
        secrets.set(SecretKey.SUPABASE_ACCESS_TOKEN, accessToken)
        secrets.set(SecretKey.SUPABASE_REFRESH_TOKEN, refreshToken)
        return true
    }

    private data class Credentials(val baseUrl: String, val anonKey: String, val accessToken: String, val refreshToken: String)

    private suspend fun credentials(): Credentials? {
        val baseUrl = secrets.get(SecretKey.SUPABASE_URL)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val anonKey = secrets.get(SecretKey.SUPABASE_ANON_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val accessToken = secrets.get(SecretKey.SUPABASE_ACCESS_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = secrets.get(SecretKey.SUPABASE_REFRESH_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        return Credentials(baseUrl, anonKey, accessToken, refreshToken)
    }

    private fun requestBody(p: SyncedProfile): JsonObject = buildJsonObject {
        put("effective_at", p.effectiveAt.toString())
        put("sex", p.body.sex.name)
        put("age_years", p.body.ageYears)
        put("height_cm", p.body.heightCm)
        put("weight_kg", p.body.weightKg)
        put("activity_factor", p.body.activityFactor)
        put("goal_phase", p.goal.phase.name)
        put("budget_offset_kcal", p.goal.budgetOffsetKcal)
        put("protein_per_kg", p.goal.proteinPerKg)
        put("training_days", buildJsonArray { p.trainingDays.forEach { add(JsonPrimitive(it.name)) } })
        put("time_budget_minutes", p.timeBudgetMinutes)
        put("source", if (p.source == ProfileSource.CLAUDE) "claude" else "app")
        p.note?.let { put("note", it) }
    }

    /** PostgREST returns a JSON array; `current_profile` is `distinct on (user_id)`, so at most one row. */
    internal fun parse(text: String): SyncedProfile? {
        val rows = json.parseToJsonElement(text) as? JsonArray ?: return null
        val row = rows.firstOrNull()?.jsonObject ?: return null

        fun str(key: String): String? = (row[key] as? JsonPrimitive)?.contentOrNull
        fun int(key: String): Int? = (row[key] as? JsonPrimitive)?.intOrNull
        fun double(key: String): Double? = (row[key] as? JsonPrimitive)?.doubleOrNull

        val body = BodyProfile(
            sex = Sex.valueOf(str("sex") ?: return null),
            ageYears = int("age_years") ?: return null,
            heightCm = double("height_cm") ?: return null,
            weightKg = double("weight_kg") ?: return null,
            activityFactor = double("activity_factor") ?: return null,
        )
        val phase = GoalPhase.valueOf(str("goal_phase") ?: return null)
        val goal = GoalSettings(
            phase = phase,
            budgetOffsetKcal = int("budget_offset_kcal") ?: phase.defaultBudgetOffsetKcal,
            proteinPerKg = double("protein_per_kg") ?: phase.defaultProteinPerKg,
        )
        val trainingDays = (row["training_days"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.map { DayOfWeek.valueOf(it) }
            ?.toSet() ?: emptySet()
        val timeBudgetMinutes = int("time_budget_minutes") ?: return null
        val effectiveAt = Instant.parse(str("effective_at") ?: return null)
        val source = if (str("source") == "claude") ProfileSource.CLAUDE else ProfileSource.APP
        val note = str("note")

        return SyncedProfile(body, goal, trainingDays, timeBudgetMinutes, effectiveAt, source, note)
    }
}
