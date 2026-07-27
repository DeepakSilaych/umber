package com.deepak.umber.ml

import com.deepak.umber.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

class FeatureHasherTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val noon: Long = LocalDateTime.of(2025, 7, 12, 12, 30)
        .atZone(zone).toInstant().toEpochMilli()

    private fun norm(v: SparseVector): Float {
        var sum = 0f
        for (x in v.values) sum += x * x
        return sqrt(sum)
    }

    @Test
    fun `hashing is deterministic`() {
        val a = FeatureHasher.vectorize(listOf("m=swiggy", "g3=swi"))
        val b = FeatureHasher.vectorize(listOf("m=swiggy", "g3=swi"))

        assertTrue(a.indices.contentEquals(b.indices))
        assertTrue(a.values.contentEquals(b.values))
    }

    /**
     * Without L2 normalisation a verbose bank template would produce a much larger vector than a
     * terse one, and the same learning rate would take wildly different-sized steps.
     */
    @Test
    fun `vectors are unit length`() {
        val v = FeatureHasher.vectorize(listOf("a", "b", "c", "d", "e", "f"))
        assertTrue("norm was ${norm(v)}", abs(norm(v) - 1f) < 1e-4f)
    }

    @Test
    fun `empty feature list yields an empty vector`() {
        val v = FeatureHasher.vectorize(emptyList())
        assertEquals(0, v.size)
    }

    @Test
    fun `every bucket is within range`() {
        val v = FeatureHasher.vectorize((0..500).map { "feature=$it" })
        assertTrue(v.indices.all { it in 0 until FeatureHasher.DIM })
    }

    @Test
    fun `different merchants hash differently`() {
        val a = FeatureHasher.vectorize(listOf("m=swiggy"))
        val b = FeatureHasher.vectorize(listOf("m=uber"))
        assertNotEquals(a.indices.first(), b.indices.first())
    }

    /**
     * Character n-grams are what let the model recognise "SWIGGYIT" from having seen "SWIGGY" —
     * the two share almost every 3-gram even though neither whole-string feature matches.
     */
    @Test
    fun `character ngrams are emitted with boundary padding`() {
        val features = FeatureExtractor.extract(
            merchantNorm = "swiggy",
            vpaHandle = "ybl",
            channel = Channel.UPI,
            amountPaise = 45_000,
            occurredAt = noon,
            zone = zone,
        )

        assertTrue(features.contains("m=swiggy"))
        assertTrue(features.contains("g3=^sw"))
        assertTrue(features.contains("g3=gy$"))
        assertTrue(features.contains("w=swiggy"))
        assertTrue(features.contains("h=ybl"))
        assertTrue(features.contains("c=UPI"))
    }

    @Test
    fun `overlapping ngrams are shared between similar merchants`() {
        fun grams(merchant: String) = FeatureExtractor.extract(
            merchantNorm = merchant,
            vpaHandle = null,
            channel = Channel.UPI,
            amountPaise = 45_000,
            occurredAt = noon,
            zone = zone,
        ).filter { it.startsWith("g3=") }.toSet()

        val shared = grams("swiggy") intersect grams("swiggyit")
        assertTrue("expected shared trigrams, got $shared", shared.size >= 4)
    }

    @Test
    fun `amount bands separate small and large spends`() {
        fun band(paise: Long) = FeatureExtractor.extract(
            merchantNorm = "x",
            vpaHandle = null,
            channel = Channel.UPI,
            amountPaise = paise,
            occurredAt = noon,
            zone = zone,
        ).first { it.startsWith("a=") }

        assertNotEquals(band(4_000), band(50_00_000))
    }
}
