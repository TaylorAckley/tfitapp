package com.tfitapp.infrastructure.supabase

import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import com.tfitapp.application.profile.ProfileSource
import com.tfitapp.application.profile.SyncResult
import com.tfitapp.application.profile.SyncedProfile
import com.tfitapp.domain.nutrition.BodyProfile
import com.tfitapp.domain.nutrition.GoalPhase
import com.tfitapp.domain.nutrition.GoalSettings
import com.tfitapp.domain.nutrition.Sex
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseProfileClientTest {

    private fun fakeSecrets(vararg pairs: Pair<SecretKey, String>): SecretStore {
        val values = pairs.toMap().toMutableMap()
        return object : SecretStore {
            override suspend fun get(key: SecretKey): String? = values[key]
            override suspend fun set(key: SecretKey, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun observeIsSet(key: SecretKey): Flow<Boolean> = MutableStateFlow(values.containsKey(key))
        }
    }

    private fun configured(accessToken: String = "access-1", refreshToken: String = "refresh-1") = fakeSecrets(
        SecretKey.SUPABASE_URL to "https://project.supabase.co",
        SecretKey.SUPABASE_ANON_KEY to "anon-key",
        SecretKey.SUPABASE_ACCESS_TOKEN to accessToken,
        SecretKey.SUPABASE_REFRESH_TOKEN to refreshToken,
    )

    private val fixture = checkNotNull(javaClass.getResource("/supabase/current-profile.json")).readText()

    private val profile = SyncedProfile(
        body = BodyProfile(Sex.MALE, 34, 180.0, 82.5, 1.4),
        goal = GoalSettings(GoalPhase.RECOMP, 0, 1.8),
        trainingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
        timeBudgetMinutes = 45,
        effectiveAt = Instant.parse("2026-09-20T10:00:00Z"),
        source = ProfileSource.APP,
    )

    @Test
    fun `push sends the documented row shape with bearer auth and return=minimal`() = runBlocking<Unit> {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req -> captured = req; respond("", HttpStatusCode.Created) }
        val result = SupabaseProfileClient(engine, configured()).push(profile)

        assertEquals(SyncResult.Ok, result)
        val req = captured!!
        assertEquals("https://project.supabase.co/rest/v1/profile_snapshot", req.url.toString())
        assertEquals("anon-key", req.headers["apikey"])
        assertEquals("Bearer access-1", req.headers["Authorization"])
        assertEquals("return=minimal", req.headers["Prefer"])
        val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        assertEquals("MALE", body["sex"]!!.jsonPrimitive.content)
        assertEquals("RECOMP", body["goal_phase"]!!.jsonPrimitive.content)
        assertEquals("app", body["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `not configured is unavailable without a network call`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond("", HttpStatusCode.Created) }
        val result = SupabaseProfileClient(engine, fakeSecrets()).push(profile)
        assertIs<SyncResult.Unavailable>(result)
        assertEquals(0, calls)
    }

    @Test
    fun `a 401 refreshes once, stores the new tokens, and retries`() = runBlocking<Unit> {
        val secrets = configured(accessToken = "stale")
        var calls = 0
        val engine = MockEngine { req ->
            calls++
            when {
                req.url.encodedPath.endsWith("/auth/v1/token") ->
                    respond("""{"access_token":"fresh","refresh_token":"refresh-2"}""", HttpStatusCode.OK)
                req.headers["Authorization"] == "Bearer stale" -> respond("", HttpStatusCode.Unauthorized)
                else -> respond("", HttpStatusCode.Created)
            }
        }
        val result = SupabaseProfileClient(engine, secrets).push(profile)

        assertEquals(SyncResult.Ok, result)
        assertEquals(3, calls, "initial 401, refresh, retried insert")
        assertEquals("fresh", secrets.get(SecretKey.SUPABASE_ACCESS_TOKEN))
        assertEquals("refresh-2", secrets.get(SecretKey.SUPABASE_REFRESH_TOKEN))
    }

    @Test
    fun `a failed refresh leaves push unavailable`() = runBlocking<Unit> {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/auth/v1/token")) respond("", HttpStatusCode.Unauthorized)
            else respond("", HttpStatusCode.Unauthorized)
        }
        val result = SupabaseProfileClient(engine, configured()).push(profile)
        assertIs<SyncResult.Unavailable>(result)
    }

    @Test
    fun `pull parses the current_profile row into a SyncedProfile`() = runBlocking<Unit> {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req -> captured = req; respond(fixture, HttpStatusCode.OK) }
        val result = SupabaseProfileClient(engine, configured()).pull()

        assertTrue(captured!!.url.toString().startsWith("https://project.supabase.co/rest/v1/current_profile"))
        assertEquals(profile, result)
    }

    @Test
    fun `pull returns null on an empty result, garbage body, or http error`() = runBlocking<Unit> {
        assertNull(SupabaseProfileClient(MockEngine { respond("[]", HttpStatusCode.OK) }, configured()).pull())
        assertNull(SupabaseProfileClient(MockEngine { respond("not json", HttpStatusCode.OK) }, configured()).pull())
        assertNull(SupabaseProfileClient(MockEngine { respond("", HttpStatusCode.InternalServerError) }, configured()).pull())
    }

    @Test
    fun `pull with no stored credentials makes no network call`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond(fixture, HttpStatusCode.OK) }
        assertNull(SupabaseProfileClient(engine, fakeSecrets()).pull())
        assertEquals(0, calls)
    }

    @Test
    fun `signIn stores the returned session and reports success`() = runBlocking<Unit> {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond("""{"access_token":"a1","refresh_token":"r1"}""", HttpStatusCode.OK)
        }
        val secrets = fakeSecrets(SecretKey.SUPABASE_URL to "https://project.supabase.co", SecretKey.SUPABASE_ANON_KEY to "anon-key")
        val ok = SupabaseProfileClient(engine, secrets).signIn("me@example.com", "hunter2")

        assertEquals(true, ok)
        assertTrue(captured!!.url.toString().startsWith("https://project.supabase.co/auth/v1/token?grant_type=password"))
        assertEquals("anon-key", captured.headers["apikey"])
        assertEquals("a1", secrets.get(SecretKey.SUPABASE_ACCESS_TOKEN))
        assertEquals("r1", secrets.get(SecretKey.SUPABASE_REFRESH_TOKEN))
    }

    @Test
    fun `signIn with wrong credentials fails without storing anything`() = runBlocking<Unit> {
        val secrets = fakeSecrets(SecretKey.SUPABASE_URL to "https://project.supabase.co", SecretKey.SUPABASE_ANON_KEY to "anon-key")
        val ok = SupabaseProfileClient(MockEngine { respond("", HttpStatusCode.BadRequest) }, secrets).signIn("me@example.com", "wrong")
        assertEquals(false, ok)
        assertNull(secrets.get(SecretKey.SUPABASE_ACCESS_TOKEN))
    }
}
