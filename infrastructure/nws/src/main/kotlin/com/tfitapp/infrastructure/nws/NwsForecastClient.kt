package com.tfitapp.infrastructure.nws

import com.tfitapp.application.port.ForecastPort
import com.tfitapp.domain.time.WallClock
import com.tfitapp.domain.training.Forecast
import com.tfitapp.domain.training.HourlyForecast
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.time.Duration as KDuration

/**
 * National Weather Service hourly forecast. Two calls the first time — `/points`
 * resolves the grid, then the hourly URL it returns — and one thereafter, because
 * the grid for a fixed home never changes. Forecasts are cached for an hour; the
 * caller decides how stale is too stale. US only, by design.
 */
public class NwsForecastClient(
    engine: HttpClientEngine,
    private val clock: WallClock,
    private val baseUrl: String = DEFAULT_BASE_URL,
    userAgent: String = DEFAULT_USER_AGENT,
) : ForecastPort {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine) {
        expectSuccess = false
        install(io.ktor.client.plugins.DefaultRequest) {
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Accept, "application/geo+json")
        }
    }

    private var gridCache: Pair<String, String>? = null
    private var forecastCache: Pair<String, Forecast>? = null

    override suspend fun hourly(latitude: Double, longitude: Double, timeout: KDuration): Forecast? {
        val key = String.format(Locale.ROOT, "%.2f,%.2f", latitude, longitude)
        val now = clock.now()
        forecastCache?.let { (k, f) -> if (k == key && Duration.between(f.fetchedAt, now) < CACHE_TTL) return f }
        return withTimeoutOrNull(timeout) {
            runCatching {
                val hourlyUrl = gridCache?.takeIf { it.first == key }?.second ?: resolveGrid(key).also { gridCache = key to it }
                val text = http.get(hourlyUrl).let { r -> if (r.status.value in 200..299) r.bodyAsText() else return@runCatching null }
                parseHourly(text, now).also { forecastCache = key to it }
            }.getOrNull()
        }
    }

    private suspend fun resolveGrid(key: String): String {
        val r = http.get("$baseUrl/points/$key")
        check(r.status.value in 200..299) { "NWS points HTTP ${r.status.value}" }
        val props = json.parseToJsonElement(r.bodyAsText()).jsonObject["properties"] as? JsonObject ?: error("no properties")
        return (props["forecastHourly"] as? JsonPrimitive)?.contentOrNull ?: error("no forecastHourly")
    }

    internal fun parseHourly(text: String, fetchedAt: Instant): Forecast {
        val props = json.parseToJsonElement(text).jsonObject["properties"] as? JsonObject ?: error("no properties")
        val periods = props["periods"] as? JsonArray ?: error("no periods")
        val hours = periods.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val start = (o["startTime"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
            val temp = (o["temperature"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val tempF = if ((o["temperatureUnit"] as? JsonPrimitive)?.contentOrNull == "C") temp * 9 / 5 + 32 else temp
            HourlyForecast(
                startsAt = start,
                tempF = tempF,
                windMph = parseWind((o["windSpeed"] as? JsonPrimitive)?.contentOrNull),
                precipProbabilityPct = ((o["probabilityOfPrecipitation"] as? JsonObject)?.get("value") as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
                relativeHumidityPct = ((o["relativeHumidity"] as? JsonObject)?.get("value") as? JsonPrimitive)?.doubleOrNull?.toInt(),
                shortForecast = (o["shortForecast"] as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }
        return Forecast(fetchedAt, hours)
    }

    /** NWS gives "10 mph" or "5 to 10 mph"; take the upper bound. */
    private fun parseWind(text: String?): Double =
        text?.let { Regex("""\d+(\.\d+)?""").findAll(it).mapNotNull { m -> m.value.toDoubleOrNull() }.maxOrNull() } ?: 0.0

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.weather.gov"
        /** NWS requires an identifying User-Agent. Override with a contact address in the app. */
        public const val DEFAULT_USER_AGENT: String = "TFitApp/0.1 (personal fitness app)"
        private val CACHE_TTL: Duration = Duration.ofHours(1)
    }
}
