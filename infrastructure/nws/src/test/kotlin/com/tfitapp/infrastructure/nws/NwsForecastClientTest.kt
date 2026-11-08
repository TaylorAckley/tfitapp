package com.tfitapp.infrastructure.nws

import com.tfitapp.domain.time.WallClock
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class NwsForecastClientTest {

    private val points = checkNotNull(javaClass.getResource("/nws/points.json")).readText()
    private val hourly = checkNotNull(javaClass.getResource("/nws/hourly.json")).readText()
    private val t0 = Instant.parse("2026-09-19T20:00:00Z")

    private class FakeClock(var now: Instant) : WallClock {
        override fun now() = now
    }

    @Test
    fun `resolves the grid once, sends a user agent, and parses hourly periods`() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val engine = MockEngine { req ->
            urls += req.url.toString()
            assertNotNull(req.headers[HttpHeaders.UserAgent])
            when {
                "/points/" in req.url.encodedPath -> respond(points, HttpStatusCode.OK)
                else -> respond(hourly, HttpStatusCode.OK)
            }
        }
        val clock = FakeClock(t0)
        val client = NwsForecastClient(engine, clock)

        val f = client.hourly(47.6062, -122.3321, 5.seconds)!!
        assertEquals("https://api.weather.gov/points/47.61,-122.33", urls[0], "two-decimal coordinate")
        assertEquals("https://api.weather.gov/gridpoints/SEW/124,67/forecast/hourly", urls[1])
        assertEquals(2, f.hours.size)
        val h = f.hours[0]
        assertEquals(72.0, h.tempF)
        assertEquals(10.0, h.windMph, "upper bound of '5 to 10 mph'")
        assertEquals(10, h.precipProbabilityPct)
        assertEquals(55, h.relativeHumidityPct)
        assertEquals("Mostly Sunny", h.shortForecast)
        assertEquals(0, f.hours[1].precipProbabilityPct, "null probability → 0")
        assertEquals(Instant.parse("2026-09-20T00:00:00Z"), h.startsAt)

        // Within the TTL: served from cache, no calls.
        clock.now = t0.plusSeconds(1800)
        client.hourly(47.6062, -122.3321, 5.seconds)
        assertEquals(2, urls.size)

        // After the TTL: refetch hourly only — the grid is remembered.
        clock.now = t0.plusSeconds(3700)
        client.hourly(47.6062, -122.3321, 5.seconds)
        assertEquals(3, urls.size)
        assertEquals("https://api.weather.gov/gridpoints/SEW/124,67/forecast/hourly", urls[2])
    }

    @Test
    fun `failures return null rather than throwing`() = runBlocking<Unit> {
        val engine = MockEngine { respond("boom", HttpStatusCode.ServiceUnavailable) }
        assertNull(NwsForecastClient(engine, FakeClock(t0)).hourly(47.6, -122.3, 5.seconds))
    }
}
