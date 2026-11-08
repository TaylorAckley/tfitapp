package com.tfitapp.application.training

import com.tfitapp.application.judgement.Answer
import com.tfitapp.application.judgement.JevQuestions
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.judgement.Question
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.domain.time.WallClock
import com.tfitapp.domain.timer.IntervalPlan
import com.tfitapp.domain.timer.Phase
import com.tfitapp.domain.timer.PhaseKind
import com.tfitapp.domain.training.BlockFeedback
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.BlockKind
import com.tfitapp.domain.training.CandidateAxis
import com.tfitapp.domain.training.FeedbackSource
import com.tfitapp.domain.training.LastPerformance
import com.tfitapp.domain.training.Location
import com.tfitapp.domain.training.Pattern
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionBlock
import com.tfitapp.domain.training.SessionCandidate
import com.tfitapp.domain.training.SetLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class CheckOutTest {

    private val now = Instant.parse("2026-09-19T18:30:00Z")
    private val plan = IntervalPlan("p", "Circuit", false, listOf(Phase("Burpees", PhaseKind.WORK, 30), Phase("Rest", PhaseKind.REST, 30), Phase("Star jumps", PhaseKind.WORK, 30)))
    private val blocks = listOf(
        SessionBlock("b1", 0, BlockKind.STRENGTH, Pattern.HINGE, listOf("ex.swing"), "KB swing", Prescription.SetsReps(4, 10, 15, 90, "45 lb"), 8),
        SessionBlock("b2", 1, BlockKind.SLED, Pattern.SLED_PUSH, listOf("ex.tank"), "Tank push", Prescription.Intervals(plan, sledSetting = 4), 10),
        SessionBlock("b3", 2, BlockKind.CIRCUIT, Pattern.CONDITIONING, listOf("ex.burpee", "ex.star"), "Circuit", Prescription.Circuit(listOf("Burpees", "Star jumps"), plan), 8),
    )
    private val session = Session("s1", LocalDate.of(2026, 9, 19), "slot.a", SessionCandidate("c1", CandidateAxis.DEFAULT, "Day A", Location.OUTDOOR, blocks), emptyList(), null, null, now, now)

    private class RecordingJev(val answers: Map<String, Answer>) : JudgementPort {
        var state: JsonElement? = null
        var questions: Map<String, Question> = emptyMap()
        override suspend fun judge(state: JsonElement, questions: Map<String, Question>, timeout: Duration): JudgementResult {
            this.state = state
            this.questions = questions
            return JudgementResult.Answers(answers, "jev-latest")
        }
    }

    private class InMemorySessions : SessionRepository {
        val sessions = linkedMapOf<String, Session>()
        val feedback = linkedMapOf<String, List<BlockFeedback>>()
        override suspend fun upsert(session: Session) { sessions[session.id] = session }
        override suspend fun get(id: String) = sessions[id]
        override fun observeLatest(): Flow<Session?> = MutableStateFlow(sessions.values.lastOrNull())
        override suspend fun recent(limit: Int) = sessions.values.toList().takeLast(limit)
        override suspend fun upsertSetLog(log: SetLog) = throw UnsupportedOperationException()
        override suspend fun setLogs(sessionId: String) = emptyList<SetLog>()
        override suspend fun lastUsed() = emptyMap<String, LocalDate>()
        override suspend fun lastPerformances() = emptyMap<String, LastPerformance>()
        override suspend fun replaceFeedback(sessionId: String, feedback: List<BlockFeedback>) { this.feedback[sessionId] = feedback }
        override suspend fun feedback(sessionId: String) = feedback[sessionId].orEmpty()
    }

    @Test
    fun `one choice per block, blocks described by title and movements`() = runTest {
        val jev = RecordingJev(
            mapOf(
                JevQuestions.blockFeelId(0) to Answer.Choice(JevQuestions.FEEL_TOO_EASY, emptyMap(), 0.9),
                JevQuestions.blockFeelId(1) to Answer.Choice(JevQuestions.FEEL_TOO_HARD, emptyMap(), 0.8),
                JevQuestions.blockFeelId(2) to Answer.Choice(JevQuestions.FEEL_NOT_MENTIONED, emptyMap(), 0.7),
            ),
        )
        val reading = ReadCheckOut(jev)(session, "swings were easy, Tank at 4 was brutal")
        assertTrue(reading.jevUsed)
        assertEquals(mapOf("b1" to BlockFeel.TOO_EASY, "b2" to BlockFeel.TOO_HARD), reading.feels)
        assertEquals(3, jev.questions.size)
        val described = checkNotNull(jev.state).jsonObject.getValue("blocks").jsonArray
        assertEquals("Burpees", described[2].jsonObject.getValue("movements").jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `a low-confidence reading is dropped so the user's own tap stands`() = runTest {
        val jev = RecordingJev(mapOf(JevQuestions.blockFeelId(0) to Answer.Choice(JevQuestions.FEEL_TOO_HARD, emptyMap(), 0.3)))
        assertEquals(emptyMap(), ReadCheckOut(jev)(session, "meh").feels)
    }

    @Test
    fun `blank text asks nothing`() = runTest {
        val jev = RecordingJev(emptyMap())
        val reading = ReadCheckOut(jev)(session, "   ")
        assertFalse(reading.jevUsed)
        assertTrue(jev.questions.isEmpty())
    }

    @Test
    fun `jev down degrades with a note`() = runTest {
        val down = object : JudgementPort {
            override suspend fun judge(state: JsonElement, questions: Map<String, Question>, timeout: Duration) = JudgementResult.Unavailable("no key")
        }
        val reading = ReadCheckOut(down)(session, "all good")
        assertFalse(reading.jevUsed)
        assertEquals("no key", reading.jevNote)
    }

    @Test
    fun `save writes the sentence and one row per placed block, and saving twice is the same state`() = runTest {
        val repo = InMemorySessions()
        repo.upsert(session)
        val save = SaveCheckOut(repo, WallClock { now })
        val feels = mapOf("b1" to BlockFeel.TOO_EASY, "b2" to BlockFeel.TOO_HARD)
        val sources = mapOf("b1" to FeedbackSource.JEV, "b2" to FeedbackSource.MANUAL)
        save(session, " swings easy ", feels, sources)
        save(session, " swings easy ", feels, sources)
        assertEquals("swings easy", checkNotNull(repo.get("s1")).checkOutText)
        val rows = repo.feedback("s1")
        assertEquals(listOf("b1", "b2"), rows.map { it.blockId })
        assertEquals(listOf("ex.swing", "ex.tank"), rows.map { it.exerciseId })
        assertEquals(FeedbackSource.MANUAL, rows[1].source)
        assertEquals("s1:b1", rows[0].id)
    }
}
