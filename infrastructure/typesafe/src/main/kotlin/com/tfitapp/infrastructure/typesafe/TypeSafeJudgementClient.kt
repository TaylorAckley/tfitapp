package com.tfitapp.infrastructure.typesafe

import com.tfitapp.application.judgement.Answer
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.judgement.Question
import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The only code that knows TypeSafe's URL or JSON shape. Talks to
 * `api.typesafe.ai` directly — not to any gateway. Every failure becomes
 * [JudgementResult.Unavailable] with a reason; malformed individual answers are
 * dropped rather than failing the request (design basis §4.2).
 */
public class TypeSafeJudgementClient(
    engine: HttpClientEngine,
    private val secrets: SecretStore,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
) : JudgementPort {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = HttpClient(engine) { expectSuccess = false }

    override suspend fun judge(state: JsonElement, questions: Map<String, Question>, timeout: Duration): JudgementResult {
        if (questions.isEmpty()) return JudgementResult.Answers(emptyMap(), null)
        val key = secrets.get(SecretKey.TYPESAFE_API_KEY)?.takeIf { it.isNotBlank() }
            ?: return JudgementResult.Unavailable("No TypeSafe API key")
        val body = json.encodeToString(JsonElement.serializer(), requestBody(state, questions))

        return withTimeoutOrNull(timeout) {
            var attempt = 0
            while (true) {
                val outcome = runCatching { post(key, body) }.getOrElse { e -> Outcome.Failed("Network: ${e.message ?: e::class.simpleName}") }
                when {
                    outcome is Outcome.Retry && attempt == 0 -> { attempt++; delay(RETRY_DELAY) }
                    outcome is Outcome.Retry -> return@withTimeoutOrNull JudgementResult.Unavailable(outcome.reason)
                    outcome is Outcome.Failed -> return@withTimeoutOrNull JudgementResult.Unavailable(outcome.reason)
                    outcome is Outcome.Ok -> return@withTimeoutOrNull parse(outcome.body, questions)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            JudgementResult.Unavailable("unreachable")
        } ?: JudgementResult.Unavailable("Timed out after $timeout")
    }

    private suspend fun post(key: String, body: String): Outcome {
        val response = http.post("$baseUrl/v1/systemone") {
            header(HttpHeaders.Authorization, "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        return when (response.status.value) {
            in 200..299 -> Outcome.Ok(text)
            401 -> Outcome.Failed("TypeSafe rejected the API key (401)")
            422 -> Outcome.Failed("TypeSafe rejected the request (422) — this is a bug in our question shape")
            429 -> Outcome.Retry("Rate limited (429)")
            529, 503 -> Outcome.Retry("TypeSafe overloaded (${response.status.value})")
            else -> Outcome.Failed("TypeSafe HTTP ${response.status.value}")
        }
    }

    private fun parse(text: String, questions: Map<String, Question>): JudgementResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            return JudgementResult.Unavailable("Unparseable response")
        }
        val answers = root["answers"] as? JsonObject ?: return JudgementResult.Unavailable("Response had no answers")
        val parsed = buildMap {
            questions.forEach { (id, q) ->
                val a = answers[id] as? JsonObject ?: return@forEach
                parseAnswer(a, q)?.let { put(id, it) }
            }
        }
        return JudgementResult.Answers(parsed, (root["model"] as? JsonPrimitive)?.content)
    }

    /** Type must agree with the question asked; anything else is treated as "no answer". */
    private fun parseAnswer(a: JsonObject, q: Question): Answer? {
        val type = (a["type"] as? JsonPrimitive)?.content
        return when (q) {
            is Question.Noul -> if (type == "noul") a.double("noul")?.let(Answer::Noul) else null
            is Question.Choice -> if (type == "choice") {
                val choice = (a["choice"] as? JsonPrimitive)?.content ?: return null
                Answer.Choice(choice, a.probabilities(), a.double("confidence") ?: return null)
            } else null
            is Question.Score -> if (type == "score") {
                Answer.Score(a.double("score") ?: return null, a.probabilities(), a.double("confidence") ?: return null)
            } else null
        }
    }

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.probabilities(): Map<String, Double> =
        (this["probabilities"] as? JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.let { k to it } }?.toMap() ?: emptyMap()

    private fun requestBody(state: JsonElement, questions: Map<String, Question>): JsonElement = buildJsonObject {
        put("state", state)
        put("model", model)
        put("questions", buildJsonObject {
            questions.forEach { (id, q) -> put(id, questionJson(q)) }
        })
    }

    internal fun questionJson(q: Question): JsonElement = buildJsonObject {
        put("instructions", q.instructions)
        when (q) {
            is Question.Noul -> {
                put("type", "noul")
                if (q.trueMeans != null || q.falseMeans != null) {
                    put("criteria", buildJsonObject {
                        q.trueMeans?.let { put("true", it) }
                        q.falseMeans?.let { put("false", it) }
                    })
                }
            }
            is Question.Choice -> {
                put("type", "choice")
                put("criteria", buildJsonObject { q.options.forEach { (k, v) -> put(k, v) } })
            }
            is Question.Score -> {
                put("type", "score")
                put("criteria", buildJsonArray { q.levels.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private sealed interface Outcome {
        data class Ok(val body: String) : Outcome
        data class Retry(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.typesafe.ai"
        public const val DEFAULT_MODEL: String = "jev-latest"
        private val RETRY_DELAY = 400.milliseconds
    }
}
