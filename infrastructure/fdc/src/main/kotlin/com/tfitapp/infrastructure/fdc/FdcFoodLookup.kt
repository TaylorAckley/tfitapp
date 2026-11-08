package com.tfitapp.infrastructure.fdc

import com.tfitapp.application.port.FoodLookupPort
import com.tfitapp.application.port.SecretKey
import com.tfitapp.application.port.SecretStore
import com.tfitapp.domain.nutrition.Food
import com.tfitapp.domain.nutrition.FoodOrigin
import com.tfitapp.domain.nutrition.Portion
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration

/**
 * USDA FoodData Central search. Called only when the local database misses. Results
 * are per 100 g at the source, which is exactly how [Food] stores them. Returns an
 * empty list on any failure — a miss here becomes a quick-add row, never an error.
 */
public class FdcFoodLookup(
    engine: HttpClientEngine,
    private val secrets: SecretStore,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : FoodLookupPort {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine) { expectSuccess = false }

    override suspend fun search(query: String, limit: Int, timeout: Duration): List<Food> {
        val key = secrets.get(SecretKey.FDC_API_KEY)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return withTimeoutOrNull(timeout) {
            runCatching {
                val response = http.get("$baseUrl/v1/foods/search") {
                    header("X-Api-Key", key)
                    parameter("query", query)
                    parameter("pageSize", limit)
                    parameter("dataType", "Foundation,SR Legacy,Branded")
                }
                if (response.status.value !in 200..299) return@runCatching emptyList()
                parse(response.bodyAsText(), limit)
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    internal fun parse(text: String, limit: Int): List<Food> {
        val root = json.parseToJsonElement(text).jsonObject
        val foods = root["foods"] as? JsonArray ?: return emptyList()
        return foods.mapNotNull { (it as? JsonObject)?.let(::toFood) }.take(limit)
    }

    private fun toFood(o: JsonObject): Food? {
        val fdcId = (o["fdcId"] as? JsonPrimitive)?.intOrNull ?: return null
        val description = (o["description"] as? JsonPrimitive)?.contentOrNull ?: return null
        val nutrients = (o["foodNutrients"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        fun nutrient(id: Int): Double? = nutrients.firstOrNull { (it["nutrientId"] as? JsonPrimitive)?.intOrNull == id }
            ?.let { (it["value"] as? JsonPrimitive)?.doubleOrNull }
        val kcal = nutrient(ENERGY_KCAL) ?: return null
        val protein = nutrient(PROTEIN) ?: 0.0
        val brand = (o["brandOwner"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val name = brand?.let { "$description ($it)" } ?: description.lowercase().replaceFirstChar { it.uppercase() }
        return Food(
            id = "fdc:$fdcId",
            name = name,
            kcalPer100g = kcal,
            proteinPer100g = protein,
            carbsPer100g = nutrient(CARBS),
            fatPer100g = nutrient(FAT),
            portions = portions(o),
            origin = FoodOrigin.FDC,
            externalId = fdcId.toString(),
        )
    }

    private fun portions(o: JsonObject): List<Portion> {
        val out = mutableListOf<Portion>()
        // Branded: one labelled serving.
        val serving = (o["servingSize"] as? JsonPrimitive)?.doubleOrNull
        val unit = (o["servingSizeUnit"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (serving != null && serving > 0 && (unit == "g" || unit == "ml")) {
            val text = (o["householdServingFullText"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            out += Portion(text ?: "1 serving (${serving.toInt()} $unit)", serving)
        }
        // SR Legacy / Foundation: household measures.
        (o["foodMeasures"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { m ->
            val grams = (m["gramWeight"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
            val label = (m["disseminationText"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            if (grams > 0 && label.isNotBlank() && !label.equals("Quantity not specified", ignoreCase = true)) out += Portion(label, grams)
        }
        return out.distinctBy { it.label }
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.nal.usda.gov/fdc"
        private const val ENERGY_KCAL = 1008
        private const val PROTEIN = 1003
        private const val CARBS = 1005
        private const val FAT = 1004
    }
}
