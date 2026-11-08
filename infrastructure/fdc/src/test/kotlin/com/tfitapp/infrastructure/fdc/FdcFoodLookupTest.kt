package com.tfitapp.infrastructure.fdc

import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import com.tfitapp.domain.nutrition.FoodOrigin
import com.tfitapp.domain.nutrition.Portion
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FdcFoodLookupTest {

    private fun secrets(key: String?) = object : SecretStore {
        override suspend fun get(key0: SecretKey) = key
        override suspend fun set(key0: SecretKey, value: String?) {}
        override fun observeIsSet(key0: SecretKey): Flow<Boolean> = flowOf(key != null)
    }

    private val fixture = checkNotNull(javaClass.getResource("/fdc/search.json")).readText()

    @Test
    fun `maps nutrients per 100g, portions from both branded and legacy shapes, and skips rows without energy`() = runBlocking<Unit> {
        var captured: HttpRequestData? = null
        val engine = MockEngine { req -> captured = req; respond(fixture, HttpStatusCode.OK) }
        val foods = FdcFoodLookup(engine, secrets("key-1")).search("egg", 5, 3.seconds)

        val req = captured!!
        assertEquals("key-1", req.headers["X-Api-Key"])
        assertTrue(req.url.toString().startsWith("https://api.nal.usda.gov/fdc/v1/foods/search?"))
        assertNull(req.url.parameters["api_key"], "key must not travel in the query string")

        assertEquals(2, foods.size, "the row without an energy value is dropped")
        val egg = foods[0]
        assertEquals("fdc:748967", egg.id)
        assertEquals(148.0, egg.kcalPer100g)
        assertEquals(12.4, egg.proteinPer100g)
        assertEquals(listOf(Portion("1 large", 50.3)), egg.portions, "'Quantity not specified' is filtered")
        assertEquals(FoodOrigin.FDC, egg.origin)
        assertEquals("748967", egg.externalId)

        val bar = foods[1]
        assertTrue("Quest" in bar.name)
        assertEquals(listOf(Portion("1 bar", 60.0)), bar.portions)
    }

    @Test
    fun `no key means no call and no results`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond(fixture, HttpStatusCode.OK) }
        assertEquals(emptyList(), FdcFoodLookup(engine, secrets(null)).search("egg", 5, 3.seconds))
        assertEquals(0, calls)
    }

    @Test
    fun `http errors and garbage bodies become empty lists`() = runBlocking<Unit> {
        assertEquals(emptyList(), FdcFoodLookup(MockEngine { respond("nope", HttpStatusCode.InternalServerError) }, secrets("k")).search("egg", 5, 3.seconds))
        assertEquals(emptyList(), FdcFoodLookup(MockEngine { respond("not json", HttpStatusCode.OK) }, secrets("k")).search("egg", 5, 3.seconds))
    }
}
