package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.ExerciseRepository
import com.tfitapp.domain.training.Exercise
import com.tfitapp.domain.training.ExerciseLibrary
import com.tfitapp.infrastructure.room.dao.ExerciseDao
import com.tfitapp.infrastructure.room.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exercises as JSON rows. Seeded from [ExerciseLibrary] once; user edits (weights, exclusions) persist. */
class RoomExerciseRepository(private val dao: ExerciseDao) : ExerciseRepository {

    private val seedLock = Mutex()
    private var seeded = false

    override fun observeAll(): Flow<List<Exercise>> =
        dao.observeAll().onStart { ensureSeeded() }.map { rows -> rows.map(::decode) }

    override suspend fun all(): List<Exercise> {
        ensureSeeded()
        return dao.all().map(::decode)
    }

    override suspend fun upsert(exercise: Exercise) =
        dao.upsert(ExerciseEntity(exercise.id, roomJson.encodeToString(Exercise.serializer(), exercise)))

    private suspend fun ensureSeeded() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            if (dao.count() == 0) {
                dao.upsertAll(ExerciseLibrary.seed().map { ExerciseEntity(it.id, roomJson.encodeToString(Exercise.serializer(), it)) })
            }
            seeded = true
        }
    }

    private fun decode(row: ExerciseEntity) = roomJson.decodeFromString(Exercise.serializer(), row.json)
}
