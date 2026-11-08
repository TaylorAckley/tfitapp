package com.tfitapp.domain.training

import com.tfitapp.domain.training.EquipmentKind.BATTLE_ROPE
import com.tfitapp.domain.training.EquipmentKind.BODY
import com.tfitapp.domain.training.EquipmentKind.JUMP_ROPE
import com.tfitapp.domain.training.EquipmentKind.KETTLEBELL
import com.tfitapp.domain.training.EquipmentKind.SLED
import com.tfitapp.domain.training.Location.INDOOR
import com.tfitapp.domain.training.Location.OUTDOOR

/**
 * Seed library built around the gear in [Equipment.seed]. Liked movements carry a
 * preference weight above 1. Ladders are placeholders until the user says what
 * "harder" means to them (design basis §11).
 */
public object ExerciseLibrary {
    private val anywhere = setOf(OUTDOOR, INDOOR)
    private val outdoorOnly = setOf(OUTDOOR)

    public fun seed(): List<Exercise> = listOf(
        // Warm-up
        Exercise("ex.kb-halo", "KB halo (10 lb)", setOf(Pattern.WARMUP), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),
        Exercise("ex.mountain-climber", "Mountain climbers", setOf(Pattern.WARMUP, Pattern.CORE, Pattern.CONDITIONING), setOf(BODY), anywhere, LoadModel.TIMED, preferenceWeight = 1.3),
        Exercise("ex.jump-rope-easy", "Jump rope (easy)", setOf(Pattern.WARMUP), setOf(JUMP_ROPE), anywhere, LoadModel.TIMED),

        // Kettlebell strength (45 lb) — fixed load, progress reps → density → variation
        Exercise("ex.kb-swing", "KB swing", setOf(Pattern.HINGE, Pattern.CONDITIONING), setOf(KETTLEBELL), anywhere, LoadModel.FIXED, ladder = listOf("ex.kb-swing-1arm")),
        Exercise("ex.kb-swing-1arm", "Single-arm KB swing", setOf(Pattern.HINGE), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),
        Exercise("ex.kb-goblet-squat", "Goblet squat", setOf(Pattern.SQUAT), setOf(KETTLEBELL), anywhere, LoadModel.FIXED, ladder = listOf("ex.kb-clean-squat")),
        Exercise("ex.kb-clean-squat", "KB clean + front squat", setOf(Pattern.SQUAT), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),
        Exercise("ex.kb-press", "KB press", setOf(Pattern.PRESS), setOf(KETTLEBELL), anywhere, LoadModel.FIXED, ladder = listOf("ex.kb-clean-press")),
        Exercise("ex.kb-clean-press", "KB clean + press", setOf(Pattern.PRESS), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),
        Exercise("ex.kb-row", "Single-arm KB row", setOf(Pattern.PULL), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),
        Exercise("ex.kb-carry", "Suitcase carry", setOf(Pattern.CARRY), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),
        Exercise("ex.kb-tgu", "Turkish get-up", setOf(Pattern.CORE, Pattern.PRESS), setOf(KETTLEBELL), anywhere, LoadModel.FIXED),

        // Bodyweight — liked movements weighted up
        Exercise("ex.pushup", "Push-ups", setOf(Pattern.PRESS), setOf(BODY), anywhere, LoadModel.FIXED, ladder = listOf("ex.pushup-decline"), preferenceWeight = 1.4),
        Exercise("ex.pushup-decline", "Decline push-ups", setOf(Pattern.PRESS), setOf(BODY), anywhere, LoadModel.FIXED),
        Exercise("ex.burpee", "Burpees", setOf(Pattern.CONDITIONING, Pattern.LOCOMOTION), setOf(BODY), anywhere, LoadModel.FIXED, preferenceWeight = 1.4),
        Exercise("ex.star-jump", "Star jumps", setOf(Pattern.CONDITIONING), setOf(BODY), anywhere, LoadModel.FIXED, preferenceWeight = 1.4),
        Exercise("ex.air-squat", "Air squats", setOf(Pattern.SQUAT, Pattern.CONDITIONING), setOf(BODY), anywhere, LoadModel.FIXED),
        Exercise("ex.lunge", "Reverse lunges", setOf(Pattern.SQUAT), setOf(BODY), anywhere, LoadModel.FIXED),
        Exercise("ex.plank", "Plank", setOf(Pattern.CORE), setOf(BODY), anywhere, LoadModel.TIMED),
        Exercise("ex.bear-crawl", "Bear crawl", setOf(Pattern.LOCOMOTION, Pattern.CORE), setOf(BODY), anywhere, LoadModel.TIMED),

        // Sled — outdoor only
        Exercise("ex.sled-push", "Tank push", setOf(Pattern.SLED_PUSH, Pattern.CONDITIONING), setOf(SLED), outdoorOnly, LoadModel.SLED),
        Exercise("ex.sled-reverse-drag", "Tank reverse drag", setOf(Pattern.SLED_PULL), setOf(SLED), outdoorOnly, LoadModel.SLED),
        Exercise("ex.sled-rope-pull", "Tank rope pull (hand over hand)", setOf(Pattern.SLED_PULL, Pattern.PULL), setOf(SLED), outdoorOnly, LoadModel.SLED),
        Exercise("ex.sled-wheelbarrow", "Tank wheelbarrow push", setOf(Pattern.SLED_PUSH, Pattern.CARRY), setOf(SLED), outdoorOnly, LoadModel.SLED),

        // Ropes
        Exercise("ex.rope-waves", "Battle rope alternating waves", setOf(Pattern.CONDITIONING), setOf(BATTLE_ROPE), anywhere, LoadModel.TIMED),
        Exercise("ex.rope-slams", "Battle rope slams", setOf(Pattern.CONDITIONING), setOf(BATTLE_ROPE), anywhere, LoadModel.TIMED),
        Exercise("ex.jump-rope", "Weighted jump rope", setOf(Pattern.CONDITIONING), setOf(JUMP_ROPE), anywhere, LoadModel.TIMED),

        // Lines — the modality itself; the course comes from Settings
        Exercise("ex.lines", "Lines", setOf(Pattern.LINES, Pattern.CONDITIONING), setOf(BODY), outdoorOnly, LoadModel.LINES, preferenceWeight = 1.5),
    )
}
