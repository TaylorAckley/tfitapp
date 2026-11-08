package com.tfitapp.application.nutrition

import com.tfitapp.application.port.SettingsRepository
import com.tfitapp.application.port.WeightRepository
import com.tfitapp.domain.nutrition.WeightEntry
import com.tfitapp.domain.time.WallClock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Records a weigh-in and keeps the body profile's weight current for the protein floor. */
public class LogWeight @Inject constructor(
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
    private val clock: WallClock,
    private val zone: ZoneId,
) {
    public suspend operator fun invoke(kg: Double, entryId: String = UUID.randomUUID().toString()) {
        val now = clock.now()
        weights.upsert(WeightEntry(entryId, now, now.atZone(zone).toLocalDate(), kg))
        settings.update { s -> s.copy(body = s.body?.copy(weightKg = kg)) }
    }
}
