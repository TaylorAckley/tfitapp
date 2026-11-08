package com.tfitapp.application.nutrition

import com.tfitapp.application.port.FoodLogRepository
import com.tfitapp.application.port.MealTemplateRepository
import com.tfitapp.domain.nutrition.EntrySource
import com.tfitapp.domain.nutrition.FoodLogEntry
import com.tfitapp.domain.nutrition.MealSlot
import com.tfitapp.domain.nutrition.MealTemplate
import com.tfitapp.domain.time.WallClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MealTemplatesTest {

    private val now = Instant.parse("2026-09-19T08:30:00Z")
    private val clock = WallClock { now }
    private val today = LocalDate.of(2026, 9, 19)

    private class InMemoryMeals : MealTemplateRepository {
        val rows = MutableStateFlow<Map<String, MealTemplate>>(emptyMap())
        override fun observeActive(): Flow<List<MealTemplate>> = rows.map { m -> m.values.filter { it.archivedAt == null } }
        override suspend fun active() = rows.value.values.filter { it.archivedAt == null }
        override suspend fun byNameKey(nameKey: String) = rows.value.values.firstOrNull { it.nameKey == nameKey }
        override suspend fun upsert(template: MealTemplate) { rows.value += template.id to template }
        override suspend fun archive(id: String, at: Instant) { rows.value[id]?.let { rows.value += id to it.copy(archivedAt = at) } }
    }

    private class InMemoryLog : FoodLogRepository {
        val rows = linkedMapOf<String, FoodLogEntry>()
        override suspend fun upsert(entry: FoodLogEntry) { rows[entry.id] = entry }
        override fun observeDay(date: LocalDate): Flow<List<FoodLogEntry>> = throw UnsupportedOperationException()
        override suspend fun between(from: LocalDate, to: LocalDate) = rows.values.toList()
        override suspend fun softDelete(id: String, at: Instant) { rows[id]?.let { rows[id] = it.copy(deletedAt = at) } }
    }

    private val entries = listOf(
        FoodLogEntry("e1", now, today, MealSlot.BREAKFAST, "egg", "Egg", 100.0, 143.0, 12.6, EntrySource.JEV),
        FoodLogEntry("e2", now, today, MealSlot.BREAKFAST, null, "coffee", null, 5.0, 0.0, EntrySource.QUICK),
        FoodLogEntry("e3", now, today, MealSlot.BREAKFAST, "toast", "Toast", 25.0, 73.0, 2.0, EntrySource.MANUAL, deletedAt = now),
    )

    @Test
    fun `saving the same name twice keeps one template and its id, and skips deleted entries`() = runTest {
        val meals = InMemoryMeals()
        val save = SaveMealTemplate(meals, clock)
        val first = save("Weekday breakfast", entries)
        val second = save("  weekday   BREAKFAST ", entries.take(1))
        assertEquals(first.id, second.id)
        assertEquals(1, meals.active().size)
        assertEquals(listOf("Egg"), meals.active().single().items.map { it.label })
        assertEquals(2, first.items.size, "the soft-deleted toast never made it in")
        assertEquals(first.createdAt, second.createdAt)
    }

    @Test
    fun `saving over an archived name revives it`() = runTest {
        val meals = InMemoryMeals()
        val save = SaveMealTemplate(meals, clock)
        val t = save("Lunch", entries)
        ArchiveMealTemplate(meals, clock)(t.id)
        assertEquals(0, meals.active().size)
        save("Lunch", entries)
        assertNull(meals.active().single().archivedAt)
    }

    @Test
    fun `logging a meal writes one copy per item and the same ids twice is one set of rows`() = runTest {
        val log = InMemoryLog()
        val template = MealTemplate.fromEntries("m1", "Weekday breakfast", entries, now)
        val ids = listOf("a", "b")
        LogMealTemplate(log, clock, ZoneOffset.UTC)(template, MealSlot.SNACK, ids)
        LogMealTemplate(log, clock, ZoneOffset.UTC)(template, MealSlot.SNACK, ids)
        assertEquals(2, log.rows.size)
        assertEquals(EntrySource.COPY, log.rows.getValue("a").source)
        assertEquals(EntrySource.QUICK, log.rows.getValue("b").source, "an estimate stays an estimate")
        assertEquals(MealSlot.SNACK, log.rows.getValue("a").mealSlot)
        assertEquals(143.0, log.rows.getValue("a").kcal)
    }
}
