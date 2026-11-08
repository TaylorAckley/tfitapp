package com.tfitapp.application.training

import com.tfitapp.application.judgement.JevQuestions
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.port.ExerciseRepository
import com.tfitapp.application.port.ForecastPort
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.settings.UserSettings
import com.tfitapp.domain.time.WallClock
import com.tfitapp.domain.training.CandidateAxis
import com.tfitapp.domain.training.ComposeContext
import com.tfitapp.domain.training.Composer
import com.tfitapp.domain.training.Feasibility
import com.tfitapp.domain.training.Forecast
import com.tfitapp.domain.training.Pattern
import com.tfitapp.domain.training.ProgramTemplate
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionCandidate
import com.tfitapp.domain.training.WeatherFeasibility
import java.time.Duration
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** What the screen shows: the pick, the alternatives, and why. */
public data class SessionProposal(
    val session: Session,
    /** When Jev was unsure, both of these are offered and the user chooses. */
    val offerBoth: List<SessionCandidate>,
    val feasibility: Feasibility,
    val forecastSummary: String?,
    val jevUsed: Boolean,
    val notes: List<String>,
)

/**
 * Design basis §5.3–5.4. Code composes three candidates; NWS decides outdoor
 * feasibility by threshold; Jev reads the check-in and ranks. Everything degrades:
 * no forecast → indoor, no Jev → planned candidate.
 */
public class ProposeSession @Inject constructor(
    private val settings: SettingsRepository,
    private val exercises: ExerciseRepository,
    private val sessions: SessionRepository,
    private val forecastPort: ForecastPort,
    private val judgement: JudgementPort,
    private val clock: WallClock,
    private val zone: ZoneId,
) {
    public suspend operator fun invoke(checkIn: String?): SessionProposal {
        val s = settings.current()
        val now = clock.now()
        val today = now.atZone(zone).toLocalDate()
        val notes = mutableListOf<String>()

        val forecast = fetchForecast(s)
        val windowStart = today.atTime(s.trainingStartHour, 0).atZone(zone).toInstant()
        val feasibility = WeatherFeasibility.evaluate(forecast, windowStart, windowStart.plus(Duration.ofHours(2)), now, s.weather)
        val forecastSummary = forecast?.let { summarise(it, windowStart) }

        val program = ProgramTemplate.seed()
        val slot = program.nextSlot(s.lastSlotId)
        val library = exercises.all().map { it.copy(excluded = it.excluded || it.id in s.excludedExerciseIds) }
        val composer = Composer(library, s.equipment)
        val ctx = ComposeContext(
            today = today,
            outdoorFeasible = feasibility.outdoorOk,
            timeBudgetMinutes = s.timeBudgetMinutes,
            lastUsed = sessions.lastUsed(),
            lastPerformance = sessions.lastPerformances(),
            linesCourse = s.linesCourse,
            sledMaxSetting = s.sledMaxSetting,
            newId = { UUID.randomUUID().toString() },
        )
        var candidates = composer.compose(slot, ctx)
        if (!feasibility.outdoorOk) notes += "Indoors today: " + feasibility.reasons.joinToString(", ").replaceFirstChar(Char::lowercase)
        if (s.linesCourse == null && slot.blocks.any { it.pattern == Pattern.LINES }) notes += "No lines course in Settings, so conditioning stands in for lines"

        val recent = sessions.recent(7).map { it.candidate.title }
        val (state, questions) = JevQuestions.sessionRequest(checkIn, candidates, forecastSummary, feasibility.outdoorOk, s.timeBudgetMinutes, recent)
        val result = judgement.judge(state, questions, JevQuestions.Timeouts.session)
        val answers = result as? JudgementResult.Answers
        if (answers == null) notes += "Planned without Jev, so your check-in was not read"

        var chosen = candidates.first()
        var confidence: Double? = null
        var offerBoth = emptyList<SessionCandidate>()

        if (answers != null) {
            val upper = answers.noul(JevQuestions.CHECKIN_UPPER) ?: 0.0
            val lower = answers.noul(JevQuestions.CHECKIN_LOWER) ?: 0.0
            val lowEnergy = answers.noul(JevQuestions.CHECKIN_LOW_ENERGY) ?: 0.0
            val short = answers.noul(JevQuestions.CHECKIN_WANTS_SHORT) ?: 0.0
            val outdoorOk = answers.noul(JevQuestions.OUTDOOR_OK)

            if (outdoorOk != null && outdoorOk < JevQuestions.Thresholds.OUTDOOR_OK_PROBABILITY) {
                candidates = candidates.filter { it.location != com.tfitapp.domain.training.Location.OUTDOOR }.ifEmpty { candidates }
                notes += "Outdoor vetoed on check-in"
            }
            if (upper >= JevQuestions.Thresholds.CHECKIN_LIMITATION) notes += "Upper body flagged from your check-in. Look at the pressing blocks"
            if (lower >= JevQuestions.Thresholds.CHECKIN_LIMITATION) notes += "Lower body flagged from your check-in. Look at the squat and lines blocks"

            val pick = answers.choice(JevQuestions.SESSION_PICK)
            val picked = pick?.let { p -> candidates.firstOrNull { it.id == p.choice } }
            confidence = pick?.confidence
            chosen = when {
                short >= JevQuestions.Thresholds.CHECKIN_WANTS_SHORT -> candidates.firstOrNull { it.axis == CandidateAxis.SHORTER } ?: picked ?: candidates.first()
                lowEnergy >= JevQuestions.Thresholds.CHECKIN_LOW_ENERGY -> candidates.firstOrNull { it.axis == CandidateAxis.LIGHTER } ?: picked ?: candidates.first()
                picked != null -> picked
                else -> candidates.first()
            }
            if (pick != null && pick.confidence < JevQuestions.Thresholds.SESSION_PICK_CONFIDENCE) {
                val runnerUp = pick.probabilities.filterKeys { it != chosen.id }.maxByOrNull { it.value }?.key
                offerBoth = listOfNotNull(chosen, candidates.firstOrNull { it.id == runnerUp })
            }
        }

        val session = Session(
            id = UUID.randomUUID().toString(),
            plannedFor = today,
            slotId = slot.id,
            candidate = chosen,
            alternatives = candidates.filter { it.id != chosen.id },
            checkInText = checkIn?.takeIf { it.isNotBlank() },
            jevConfidence = confidence,
        )
        sessions.upsert(session)
        return SessionProposal(session, offerBoth, feasibility, forecastSummary, jevUsed = answers != null, notes = notes)
    }

    private suspend fun fetchForecast(s: UserSettings): Forecast? {
        val home = s.home?.rounded ?: return null
        return runCatching { forecastPort.hourly(home.latitude, home.longitude, 10.seconds) }.getOrNull()
    }

    private fun summarise(forecast: Forecast, windowStart: java.time.Instant): String {
        val hours = forecast.hoursCovering(windowStart, windowStart.plus(Duration.ofHours(2))).ifEmpty { return "no hours in window" }
        val h = hours.first()
        return "${h.tempF.toInt()}°F, wind ${h.windMph.toInt()} mph, precip ${hours.maxOf { it.precipProbabilityPct }}%, ${h.shortForecast}"
    }
}
