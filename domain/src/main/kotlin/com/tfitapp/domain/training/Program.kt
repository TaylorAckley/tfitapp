package com.tfitapp.domain.training

import kotlinx.serialization.Serializable

@Serializable
public enum class BlockKind { WARMUP, STRENGTH, CIRCUIT, SLED, LINES, CONDITIONING }

/**
 * What a slot wants at one position, without naming the exercise. The composer
 * fills it from the library.
 */
@Serializable
public data class BlockSpec(
    val kind: BlockKind,
    val pattern: Pattern,
    val sets: Int = 3,
    val repsMin: Int = 8,
    val repsMax: Int = 12,
    /** For CIRCUIT: how many movements to pull for this pattern. */
    val movements: Int = 1,
    val rounds: Int = 3,
    val workSeconds: Int = 30,
    val restSeconds: Int = 30,
    /** Rough minutes so candidates can be cut to a time budget. */
    val estimatedMinutes: Int = 6,
)

@Serializable
public data class SlotTemplate(val id: String, val name: String, val blocks: List<BlockSpec>)

@Serializable
public data class ProgramTemplate(val id: String, val name: String, val slots: List<SlotTemplate>) {
    init {
        require(slots.isNotEmpty())
    }

    /** Next slot after [lastSlotId] in rotation; the first slot when there is no history. */
    public fun nextSlot(lastSlotId: String?): SlotTemplate {
        val i = slots.indexOfFirst { it.id == lastSlotId }
        return if (i < 0) slots.first() else slots[(i + 1) % slots.size]
    }

    public companion object {
        /** First template from design basis §5.3. Editable; this is the seed. */
        public fun seed(): ProgramTemplate = ProgramTemplate(
            id = "prog.v1",
            name = "Tank, KB, lines",
            slots = listOf(
                SlotTemplate(
                    "slot.kb-strength", "KB strength + circuit",
                    listOf(
                        BlockSpec(BlockKind.WARMUP, Pattern.WARMUP, rounds = 2, workSeconds = 40, restSeconds = 20, estimatedMinutes = 4),
                        BlockSpec(BlockKind.STRENGTH, Pattern.HINGE, sets = 4, repsMin = 10, repsMax = 15, estimatedMinutes = 8),
                        BlockSpec(BlockKind.STRENGTH, Pattern.SQUAT, sets = 3, repsMin = 8, repsMax = 12, estimatedMinutes = 7),
                        BlockSpec(BlockKind.STRENGTH, Pattern.PRESS, sets = 3, repsMin = 6, repsMax = 10, estimatedMinutes = 7),
                        BlockSpec(BlockKind.CIRCUIT, Pattern.CONDITIONING, movements = 3, rounds = 3, workSeconds = 40, restSeconds = 20, estimatedMinutes = 10),
                    ),
                ),
                SlotTemplate(
                    "slot.sled", "Sled day",
                    listOf(
                        BlockSpec(BlockKind.WARMUP, Pattern.WARMUP, rounds = 2, workSeconds = 40, restSeconds = 20, estimatedMinutes = 4),
                        BlockSpec(BlockKind.SLED, Pattern.SLED_PUSH, rounds = 6, workSeconds = 30, restSeconds = 60, estimatedMinutes = 10),
                        BlockSpec(BlockKind.SLED, Pattern.SLED_PULL, rounds = 5, workSeconds = 30, restSeconds = 60, estimatedMinutes = 8),
                        BlockSpec(BlockKind.STRENGTH, Pattern.CARRY, sets = 3, repsMin = 1, repsMax = 1, estimatedMinutes = 6),
                        BlockSpec(BlockKind.CONDITIONING, Pattern.CONDITIONING, rounds = 6, workSeconds = 20, restSeconds = 20, estimatedMinutes = 5),
                    ),
                ),
                SlotTemplate(
                    "slot.lines", "Lines day",
                    listOf(
                        BlockSpec(BlockKind.WARMUP, Pattern.WARMUP, rounds = 2, workSeconds = 40, restSeconds = 20, estimatedMinutes = 4),
                        BlockSpec(BlockKind.LINES, Pattern.LINES, rounds = 3, restSeconds = 90, estimatedMinutes = 20),
                        BlockSpec(BlockKind.CONDITIONING, Pattern.CONDITIONING, rounds = 4, workSeconds = 30, restSeconds = 30, estimatedMinutes = 5),
                    ),
                ),
                SlotTemplate(
                    "slot.mixed", "Mixed conditioning",
                    listOf(
                        BlockSpec(BlockKind.WARMUP, Pattern.WARMUP, rounds = 2, workSeconds = 40, restSeconds = 20, estimatedMinutes = 4),
                        BlockSpec(BlockKind.CIRCUIT, Pattern.CONDITIONING, movements = 4, rounds = 4, workSeconds = 30, restSeconds = 15, estimatedMinutes = 14),
                        BlockSpec(BlockKind.STRENGTH, Pattern.CORE, sets = 3, repsMin = 5, repsMax = 8, estimatedMinutes = 6),
                    ),
                ),
            ),
        )
    }
}
