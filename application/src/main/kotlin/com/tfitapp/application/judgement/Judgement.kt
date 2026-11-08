package com.tfitapp.application.judgement

import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * A question for a System One model. Instructions and criteria are JSON so they can
 * be strings or structured objects (`what` / `not_for` / `examples`), per the
 * TypeSafe guidance. Question ids are for our code; the model never sees them, so
 * every question must carry its full meaning.
 */
public sealed interface Question {
    public val instructions: JsonElement

    /** Probability that a statement holds. */
    public data class Noul(
        override val instructions: JsonElement,
        val trueMeans: String? = null,
        val falseMeans: String? = null,
    ) : Question

    /** One of a defined set. Always include a no-match option when nothing may fit. */
    public data class Choice(
        override val instructions: JsonElement,
        val options: Map<String, String>,
    ) : Question {
        init {
            require(options.size >= 2) { "A choice needs at least two options" }
        }
    }

    /** Position on ordered levels; each level describes a concrete situation. */
    public data class Score(
        override val instructions: JsonElement,
        val levels: List<String>,
    ) : Question {
        init {
            require(levels.size >= 2) { "A score needs at least two levels" }
        }
    }
}

public sealed interface Answer {
    public data class Noul(val probability: Double) : Answer
    public data class Choice(val choice: String, val probabilities: Map<String, Double>, val confidence: Double) : Answer
    public data class Score(val score: Double, val probabilities: Map<String, Double>, val confidence: Double) : Answer
}

/**
 * Per-question answers. A question missing from [answers] had no usable answer —
 * malformed, absent, or wrong type. The caller decides what a gap means; it is never
 * an exception that discards the other answers (design basis §4.2).
 */
public sealed interface JudgementResult {
    public data class Answers(val answers: Map<String, Answer>, val model: String?) : JudgementResult {
        public fun noul(id: String): Double? = (answers[id] as? Answer.Noul)?.probability
        public fun choice(id: String): Answer.Choice? = answers[id] as? Answer.Choice
        public fun score(id: String): Answer.Score? = answers[id] as? Answer.Score
    }

    /** No key, offline, timeout, 5xx. The feature degrades to its non-AI path. */
    public data class Unavailable(val reason: String) : JudgementResult
}

/** Port implemented by `:infrastructure:typesafe`. Pure read; safe to retry. */
public interface JudgementPort {
    public suspend fun judge(state: JsonElement, questions: Map<String, Question>, timeout: Duration): JudgementResult
}
