package com.tfitapp.domain.training

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** One hour of NWS forecast, already normalised to the units the thresholds use. */
public data class HourlyForecast(
    val startsAt: Instant,
    val tempF: Double,
    val windMph: Double,
    val precipProbabilityPct: Int,
    val relativeHumidityPct: Int?,
    val shortForecast: String,
)

public data class Forecast(val fetchedAt: Instant, val hours: List<HourlyForecast>) {
    public fun isStale(now: Instant, maxAge: Duration = Duration.ofHours(6)): Boolean =
        Duration.between(fetchedAt, now) > maxAge

    public fun hoursCovering(start: Instant, end: Instant): List<HourlyForecast> =
        hours.filter { !it.startsAt.plusSeconds(3600).isBefore(start) && it.startsAt.isBefore(end) }
}

/** User-tunable limits for outdoor work. Defaults from design basis §5.4. */
@Serializable
public data class WeatherThresholds(
    val minTempF: Double = 25.0,
    val maxTempF: Double = 90.0,
    val maxWindMph: Double = 25.0,
    val maxPrecipPct: Int = 40,
    val blockedWords: List<String> = listOf("thunder", "ice", "sleet", "snow", "hail", "freezing"),
)

public data class Feasibility(val outdoorOk: Boolean, val reasons: List<String>) {
    public companion object {
        public fun noForecast(reason: String): Feasibility = Feasibility(false, listOf(reason))
    }
}

/**
 * Code decides whether outdoor is on the table. Jev may later veto a "yes" given
 * the check-in, but never turns a "no" into a "yes".
 */
public object WeatherFeasibility {
    public fun evaluate(
        forecast: Forecast?,
        windowStart: Instant,
        windowEnd: Instant,
        now: Instant,
        thresholds: WeatherThresholds = WeatherThresholds(),
    ): Feasibility {
        if (forecast == null) return Feasibility.noForecast("no forecast, set home in Settings")
        if (forecast.isStale(now)) return Feasibility.noForecast("the forecast is stale")
        val hours = forecast.hoursCovering(windowStart, windowEnd)
        if (hours.isEmpty()) return Feasibility.noForecast("the forecast does not cover your training hour")

        val reasons = buildList {
            hours.minOf { it.tempF }.let { if (it < thresholds.minTempF) add("Too cold (${it.toInt()}°F)") }
            hours.maxOf { it.tempF }.let { if (it > thresholds.maxTempF) add("Too hot (${it.toInt()}°F)") }
            hours.maxOf { heatIndexF(it.tempF, it.relativeHumidityPct) }.let {
                if (it > thresholds.maxTempF + 5) add("Heat index ${it.toInt()}°F")
            }
            hours.maxOf { it.windMph }.let { if (it > thresholds.maxWindMph) add("Wind ${it.toInt()} mph") }
            hours.maxOf { it.precipProbabilityPct }.let { if (it > thresholds.maxPrecipPct) add("Precipitation $it%") }
            hours.map { it.shortForecast.lowercase() }
                .firstOrNull { f -> thresholds.blockedWords.any { it in f } }
                ?.let { add("Forecast: $it") }
        }
        return Feasibility(reasons.isEmpty(), reasons)
    }

    /** NWS heat index (Rothfusz regression). Returns [tempF] when humidity is unknown or it does not apply. */
    public fun heatIndexF(tempF: Double, rhPct: Int?): Double {
        if (rhPct == null || tempF < 80.0) return tempF
        val rh = rhPct.toDouble()
        val hi = -42.379 + 2.04901523 * tempF + 10.14333127 * rh - 0.22475541 * tempF * rh -
            0.00683783 * tempF * tempF - 0.05481717 * rh * rh + 0.00122874 * tempF * tempF * rh +
            0.00085282 * tempF * rh * rh - 0.00000199 * tempF * tempF * rh * rh
        return maxOf(hi, tempF)
    }
}
