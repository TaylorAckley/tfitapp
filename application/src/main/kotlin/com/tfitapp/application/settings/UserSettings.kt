package com.tfitapp.application.settings

import com.tfitapp.domain.nutrition.BodyProfile
import com.tfitapp.domain.nutrition.GoalSettings
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.timer.LinesCourse
import com.tfitapp.domain.training.Equipment
import com.tfitapp.domain.training.WeatherThresholds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek
import java.time.Instant

/** kotlinx.serialization has no built-in [Instant] support; ISO-8601 round-trips via [Instant.toString]/[Instant.parse]. */
public object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant): Unit = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Where the user trains, for the NWS lookup. Two decimals is all NWS can use. */
@Serializable
public data class HomeLocation(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
    }

    public val rounded: HomeLocation get() = HomeLocation(round2(latitude), round2(longitude))
    private fun round2(v: Double): Double = kotlin.math.round(v * 100) / 100
}

/**
 * Everything in design basis §3, as one document. Stored as JSON in Room; the whole
 * thing is small and changes rarely, so a single row beats a table per setting.
 */
@Serializable
public data class UserSettings(
    val body: BodyProfile? = null,
    val goal: GoalSettings = GoalSettings(),
    val equipment: List<Equipment> = Equipment.seed(),
    val linesCourse: LinesCourse? = null,
    val home: HomeLocation? = null,
    val trainingDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
    val timeBudgetMinutes: Int = 45,
    /** Local hour the training window starts, for the forecast lookup. */
    val trainingStartHour: Int = 17,
    val weather: WeatherThresholds = WeatherThresholds(),
    val excludedExerciseIds: Set<String> = emptySet(),
    /** Typical meal times as local hours; drives the evening prompt. */
    val mealHours: Map<MealSlot, Int> = mapOf(MealSlot.BREAKFAST to 8, MealSlot.LUNCH to 12, MealSlot.DINNER to 19),
    val sledMaxSetting: Int = 3,
    val lastSlotId: String? = null,
    /** When [body]/[goal]/[trainingDays]/[timeBudgetMinutes] were last reconciled with Supabase (design basis §2 exception). */
    @Serializable(with = InstantIsoSerializer::class)
    val profileSyncedAt: Instant? = null,
)
