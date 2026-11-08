package com.tfitapp.domain.nutrition

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SentenceSplitterTest {

    @Test
    fun `splits on commas and connectors and pulls counts`() {
        val f = SentenceSplitter.split("2 eggs, toast with butter and black coffee")
        assertEquals(listOf("eggs", "toast", "butter", "black coffee"), f.map { it.foodText })
        assertEquals(Quantity.Count(2.0), f[0].quantity)
        assertNull(f[1].quantity)
    }

    @Test
    fun `grams and ounces become absolute mass`() {
        val f = SentenceSplitter.split("150g chicken breast; 6 oz rice")
        assertEquals("chicken breast", f[0].foodText)
        assertEquals(Quantity.Grams(150.0), f[0].quantity)
        assertEquals("rice", f[1].foodText)
        val oz = f[1].quantity as Quantity.Grams
        assertEquals(170.1, oz.grams, 0.1)
    }

    @Test
    fun `words and fractions count too`() {
        val f = SentenceSplitter.split("a banana, half an apple, 1/2 cup oats")
        assertEquals(Quantity.Count(1.0), f[0].quantity)
        assertEquals("half an apple", f[1].foodText)
        assertEquals(Quantity.Count(0.5), f[2].quantity)
        assertEquals("cup oats", f[2].foodText)
    }

    @Test
    fun `blank input yields nothing`() {
        assertEquals(emptyList(), SentenceSplitter.split("  ,, "))
    }

    @Test
    fun `but splits and without, minus, except and a leading no negate`() {
        val f = SentenceSplitter.split("usual breakfast but 3 eggs, without toast, no butter, minus the coffee")
        assertEquals(listOf("usual breakfast", "eggs", "toast", "butter", "coffee"), f.map { it.foodText })
        assertEquals(listOf(false, false, true, true, true), f.map { it.negated })
        assertEquals(Quantity.Count(3.0), f[1].quantity)
        assertEquals("except", SentenceSplitter.split("lunch except rice")[1].let { if (it.negated) "except" else "kept" })
    }

    @Test
    fun `with still splits and does not negate`() {
        val f = SentenceSplitter.split("toast with butter")
        assertEquals(listOf(false, false), f.map { it.negated })
    }
}
