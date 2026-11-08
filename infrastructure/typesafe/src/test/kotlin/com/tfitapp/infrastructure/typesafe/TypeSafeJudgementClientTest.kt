package com.tfitapp.infrastructure.typesafe

import com.tfitapp.application.judgement.Answer
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.judgement.Question
import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TypeSafeJudgementClientTest {

    private fun secrets(key: String?) = object : SecretStore {
        override suspend fun get(key0: SecretKey) = key
        override suspend fun set(key0: SecretKey, value: String?) {}
        override fun observeIsSet(key0: SecretKey): Flow<Boolean> = flowOf(key != null)
    }

    private val questions = mapOf(
        "is_urgent" to Question.Noul(JsonPrimitive("Does this convey urgency?")),
        "pick" to Question.Choice(JsonPrimitive("Which?"), mapOf("a" to "A", "b" to "B")),
        "grade" to Question.Score(JsonPrimitive("How good?"), listOf("bad", "ok", "good")),
    )

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/jev/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `sends the documented request shape with bearer auth and parses every answer type`() = runBlocking<Unit> {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req ->
            captured = req
            respond(fixture("all-types.json"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = TypeSafeJudgementClient(engine, secrets("sk-test"))
        val result = client.judge(JsonPrimitive("Help! payouts failing"), questions, 5.seconds)

        val req = captured!!
        assertEquals("https://api.typesafe.ai/v1/systemone", req.url.toString())
        assertEquals("Bearer sk-test", req.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
        assertEquals("jev-latest", body["model"]!!.jsonPrimitive.content)
        val qs = body["questions"]!!.jsonObject
        assertEquals("noul", qs["is_urgent"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("choice", qs["pick"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("A", qs["pick"]!!.jsonObject["criteria"]!!.jsonObject["a"]!!.jsonPrimitive.content)
        assertEquals("score", qs["grade"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val answers = assertIs<JudgementResult.Answers>(result)
        assertEquals(0.92, answers.noul("is_urgent"))
        assertEquals(Answer.Choice("b", mapOf("a" to 0.2, "b" to 0.8), 0.8), answers.choice("pick"))
        assertEquals(1.7, answers.score("grade")!!.score)
        assertEquals("jev-latest", answers.model)
    }

    @Test
    fun `a malformed answer is dropped and the rest survive`() = runBlocking<Unit> {
        val engine = MockEngine { respond(fixture("partial.json"), HttpStatusCode.OK) }
        val result = TypeSafeJudgementClient(engine, secrets("k")).judge(JsonPrimitive("x"), questions, 5.seconds)
        val answers = assertIs<JudgementResult.Answers>(result)
        assertEquals(0.3, answers.noul("is_urgent"))
        assertNull(answers.choice("pick"), "choice answered with wrong type → no answer")
        assertNull(answers.score("grade"), "missing → no answer")
    }

    @Test
    fun `no key means unavailable without a network call`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.OK) }
        val result = TypeSafeJudgementClient(engine, secrets(null)).judge(JsonPrimitive("x"), questions, 5.seconds)
        assertIs<JudgementResult.Unavailable>(result)
        assertEquals(0, calls)
    }

    @Test
    fun `401 and 422 are not retried and carry a reason`() = runBlocking<Unit> {
        for ((status, fragment) in listOf(HttpStatusCode.Unauthorized to "401", HttpStatusCode.UnprocessableEntity to "422")) {
            var calls = 0
            val engine = MockEngine { calls++; respond("{}", status) }
            val result = TypeSafeJudgementClient(engine, secrets("k")).judge(JsonPrimitive("x"), questions, 5.seconds)
            val u = assertIs<JudgementResult.Unavailable>(result)
            assertTrue(fragment in u.reason, u.reason)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `429 is retried once then gives up`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.TooManyRequests) }
        val result = TypeSafeJudgementClient(engine, secrets("k")).judge(JsonPrimitive("x"), questions, 5.seconds)
        assertIs<JudgementResult.Unavailable>(result)
        assertEquals(2, calls)
    }

    @Test
    fun `429 then 200 succeeds`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond("{}", HttpStatusCode.TooManyRequests) else respond(fixture("all-types.json"), HttpStatusCode.OK)
        }
        val result = TypeSafeJudgementClient(engine, secrets("k")).judge(JsonPrimitive("x"), questions, 5.seconds)
        assertIs<JudgementResult.Answers>(result)
    }

    @Test
    fun `empty question map short-circuits`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.OK) }
        val result = TypeSafeJudgementClient(engine, secrets("k")).judge(JsonPrimitive("x"), emptyMap(), 5.seconds)
        assertIs<JudgementResult.Answers>(result)
        assertEquals(0, calls)
    }
}
