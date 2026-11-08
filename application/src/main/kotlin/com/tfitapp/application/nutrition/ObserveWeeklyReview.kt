package com.tfitapp.application.nutrition

import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.port.WeightRepository
import com.tfitapp.domain.nutrition.WeeklyReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** The trend view, one tap from the Food tab. Recomputes whenever today's log, a weigh-in, settings, or the latest session changes. */
public class ObserveWeeklyReview @Inject constructor(
    private val log: FoodLogRepository,
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
    private val sessions: SessionRepository,
    private val zone: ZoneId,
) {
    public operator fun invoke(today: LocalDate): Flow<WeeklyReview> {
        val window = log.observeDay(today).map {
            log.between(today.minusDays(WeeklyReview.WEIGHT_WINDOW_DAYS.toLong() - 1), today)
        }
        val finishedThisWeek = sessions.observeLatest().map {
            val weekStart = today.minusDays(6)
            sessions.recent(RECENT_SESSIONS).count { s ->
                val day = s.finishedAt?.atZone(zone)?.toLocalDate()
                day != null && !day.isBefore(weekStart) && !day.isAfter(today)
            }
        }
        return combine(window, weights.observeAll(), settings.settings, finishedThisWeek) { entries, ws, s, finished ->
            WeeklyReview.build(
                today = today,
                entries = entries,
                weights = ws,
                body = s.body,
                goal = s.goal,
                sessionsFinished = finished,
                trainingDaysPerWeek = s.trainingDays.size,
            )
        }
    }

    private companion object {
        /** More than a week of daily sessions; the count filters by date. */
        const val RECENT_SESSIONS = 14
    }
}
