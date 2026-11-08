package com.tfitapp.application.training

import com.tfitapp.application.judgement.JevQuestions
import com.tfitapp.application.judgement.JudgementPort
import com.tfitapp.application.judgement.JudgementResult
import com.tfitapp.application.port.SessionRepository
import com.tfitapp.domain.time.WallClock
import com.tfitapp.domain.training.BlockFeedback
import com.tfitapp.domain.training.BlockFeel
import com.tfitapp.domain.training.FeedbackSource
import com.tfitapp.domain.training.Prescription
import com.tfitapp.domain.training.Session
import com.tfitapp.domain.training.SessionBlock
import javax.inject.Inject

/** What Jev read out of the check-out sentence, per block id. Blocks it did not place are absent. */
public data class CheckOutReading(val feels: Map<String, BlockFeel>, val jevUsed: Boolean, val jevNote: String?)

/**
 * Reads "swings were easy, Tank at 4 was brutal" into per-block feels. Pure read; the
 * screen shows the result as prefilled toggles the user can correct before saving.
 */
public class ReadCheckOut @Inject constructor(private val judgement: JudgementPort) {
    public suspend operator fun invoke(session: Session, text: String): CheckOutReading {
        val blocks = session.candidate.blocks
        if (text.isBlank() || blocks.isEmpty()) return CheckOutReading(emptyMap(), jevUsed = false, jevNote = null)
        val (state, questions) = JevQuestions.checkOutRequest(text, blocks, ::movementNames)
        val result = judgement.judge(state, questions, JevQuestions.Timeouts.checkOut)
        val answers = result as? JudgementResult.Answers
            ?: return CheckOutReading(emptyMap(), jevUsed = false, jevNote = (result as? JudgementResult.Unavailable)?.reason)
        val feels = blocks.mapIndexedNotNull { i, block ->
            val choice = answers.choice(JevQuestions.blockFeelId(i)) ?: return@mapIndexedNotNull null
            if (choice.confidence < JevQuestions.Thresholds.BLOCK_FEEL_CONFIDENCE) return@mapIndexedNotNull null
            JevQuestions.feelFor(choice.choice)?.let { block.id to it }
        }.toMap()
        return CheckOutReading(feels, jevUsed = true, jevNote = null)
    }

    private fun movementNames(block: SessionBlock): List<String> = when (val p = block.prescription) {
        is Prescription.Circuit -> p.movementNames
        else -> listOf(block.title)
    }
}

/** Persists the check-out: the sentence on the session, one feedback row per block the user or Jev placed. */
public class SaveCheckOut @Inject constructor(
    private val sessions: SessionRepository,
    private val clock: WallClock,
) {
    public suspend operator fun invoke(session: Session, text: String, feels: Map<String, BlockFeel>, sources: Map<String, FeedbackSource>): Session {
        val now = clock.now()
        val updated = session.copy(checkOutText = text.trim().takeIf { it.isNotBlank() })
        sessions.upsert(updated)
        val feedback = session.candidate.blocks.mapNotNull { block ->
            val feel = feels[block.id] ?: return@mapNotNull null
            val exerciseId = block.exerciseIds.firstOrNull() ?: return@mapNotNull null
            BlockFeedback(session.id, block.id, exerciseId, feel, sources[block.id] ?: FeedbackSource.MANUAL, now)
        }
        sessions.replaceFeedback(session.id, feedback)
        return updated
    }
}
