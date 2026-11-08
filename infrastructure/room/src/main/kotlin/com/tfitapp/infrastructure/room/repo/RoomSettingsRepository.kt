package com.tfitapp.infrastructure.room.repo

import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.settings.UserSettings
import com.tfitapp.infrastructure.room.dao.SettingsDao
import com.tfitapp.infrastructure.room.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomSettingsRepository(private val dao: SettingsDao) : SettingsRepository {

    private val writeLock = Mutex()

    override val settings: Flow<UserSettings> = dao.observe().map { it?.let(::decode) ?: UserSettings() }

    override suspend fun current(): UserSettings = dao.get()?.let(::decode) ?: UserSettings()

    /** Read-modify-write under a lock so two quick edits cannot lose each other. */
    override suspend fun update(transform: (UserSettings) -> UserSettings) = writeLock.withLock {
        val next = transform(current())
        dao.upsert(SettingsEntity(json = roomJson.encodeToString(UserSettings.serializer(), next)))
    }

    private fun decode(row: SettingsEntity): UserSettings =
        runCatching { roomJson.decodeFromString(UserSettings.serializer(), row.json) }.getOrElse { UserSettings() }
}
