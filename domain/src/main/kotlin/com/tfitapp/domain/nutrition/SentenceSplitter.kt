package com.tfitapp.domain.nutrition

/** One piece of a food sentence with whatever quantity the regex could pull out. */
public data class Fragment(
    val index: Int,
    val text: String,
    /** The food words with the quantity removed: "2 eggs" → "eggs". */
    val foodText: String,
    val quantity: Quantity?,
    /** "without butter", "no toast": the user is saying this was left out, not eaten. */
    val negated: Boolean = false,
)

/** A quantity the user stated explicitly. Either a count of portions or an absolute mass. */
public sealed interface Quantity {
    public data class Count(val n: Double) : Quantity
    public data class Grams(val grams: Double) : Quantity
}

/**
 * Deterministic first pass over "2 eggs, toast with butter and black coffee".
 * Splitting and number parsing are code because they are reliable; deciding what
 * "toast" is belongs to Jev.
 *
 * "but", "except", "without" and "minus" also split, and the last three negate what
 * follows, so "usual breakfast but 3 eggs, without toast" reads as a saved meal with
 * one change and one omission.
 */
public object SentenceSplitter {
    private val separators = Regex(
        """\s*(,|;|\band\b|\bwith\b|\bplus\b|\bbut\b|\bexcept\b|\bwithout\b|\bminus\b|\+|&)\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val negatingSeparators = setOf("except", "without", "minus")
    private val negatingPrefix = Regex("""^(no|without|minus|skip|skipped|hold the|held the)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val leadingCount = Regex("""^(\d+(?:\.\d+)?|\d+/\d+|½|¼|¾|a|an|one|two|three|four|five|six)\s+(?:x\s+)?(.*)$""", RegexOption.IGNORE_CASE)
    private val grams = Regex("""(\d+(?:\.\d+)?)\s*(g|grams?|gr)\b""", RegexOption.IGNORE_CASE)
    private val ounces = Regex("""(\d+(?:\.\d+)?)\s*(oz|ounces?)\b""", RegexOption.IGNORE_CASE)
    private val noiseWords = setOf("some", "a bit of", "the", "of")

    public fun split(sentence: String): List<Fragment> {
        // Walk the separators so each piece knows which word introduced it.
        val pieces = mutableListOf<Pair<String, Boolean>>()
        var start = 0
        var negateNext = false
        for (m in separators.findAll(sentence)) {
            pieces += sentence.substring(start, m.range.first) to negateNext
            negateNext = m.groupValues[1].lowercase() in negatingSeparators
            start = m.range.last + 1
        }
        pieces += sentence.substring(start) to negateNext
        return pieces
            .map { (raw, negated) -> raw.trim().trim('.', '!') to negated }
            .filter { (raw, _) -> raw.isNotBlank() }
            .mapIndexed { i, (raw, negated) ->
                val prefixed = negatingPrefix.find(raw)
                if (prefixed != null) parse(i, prefixed.groupValues[2], negated = true) else parse(i, raw, negated)
            }
    }

    private fun parse(index: Int, raw: String, negated: Boolean): Fragment {
        grams.find(raw)?.let { m ->
            return Fragment(index, raw, cleanup(raw.replace(m.value, "")), Quantity.Grams(m.groupValues[1].toDouble()), negated)
        }
        ounces.find(raw)?.let { m ->
            return Fragment(index, raw, cleanup(raw.replace(m.value, "")), Quantity.Grams(m.groupValues[1].toDouble() * 28.3495), negated)
        }
        leadingCount.find(raw)?.let { m ->
            val n = parseCount(m.groupValues[1]) ?: return Fragment(index, raw, cleanup(raw), null, negated)
            return Fragment(index, raw, cleanup(m.groupValues[2]), Quantity.Count(n), negated)
        }
        return Fragment(index, raw, cleanup(raw), null, negated)
    }

    private fun parseCount(token: String): Double? = when (token.lowercase()) {
        "a", "an", "one" -> 1.0
        "two" -> 2.0
        "three" -> 3.0
        "four" -> 4.0
        "five" -> 5.0
        "six" -> 6.0
        "½" -> 0.5
        "¼" -> 0.25
        "¾" -> 0.75
        else -> if ('/' in token) {
            val (a, b) = token.split('/')
            a.toDoubleOrNull()?.let { num -> b.toDoubleOrNull()?.takeIf { it != 0.0 }?.let { num / it } }
        } else token.toDoubleOrNull()
    }

    private fun cleanup(text: String): String {
        var t = text.trim().replace(Regex("\\s+"), " ")
        noiseWords.forEach { w -> t = t.replace(Regex("^$w\\s+", RegexOption.IGNORE_CASE), "") }
        return t.trim()
    }
}
