package com.deepak.umber.ml

import com.deepak.umber.data.model.Channel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

/** A sparse feature vector: parallel arrays of hashed index and weight. */
class SparseVector(val indices: IntArray, val values: FloatArray) {
    val size: Int get() = indices.size
}

/**
 * The hashing trick.
 *
 * Merchant strings are open-vocabulary — a new one shows up every week — so a fixed vocabulary
 * would need constant rebuilding. Hashing into a fixed [DIM]-wide space sidesteps that entirely:
 * unseen features simply land somewhere, and collisions at this width are rare enough to be noise.
 *
 * Signed hashing (each feature gets a +1/-1 sign from an independent bit of the hash) makes
 * collisions cancel in expectation rather than compound.
 */
object FeatureHasher {

    /**
     * 16384 buckets. With 15 classes the dense weight matrix is ~1 MB of floats, which is the real
     * memory cost of this design — worth knowing before you widen it.
     */
    const val DIM = 1 shl 14

    private const val FNV_OFFSET = -2128831035 // 2166136261 as a signed Int
    private const val FNV_PRIME = 16777619

    private fun fnv1a(s: String): Int {
        var h = FNV_OFFSET
        for (c in s) {
            h = h xor c.code
            h *= FNV_PRIME
        }
        return h
    }

    fun bucketOf(feature: String): Int = fnv1a(feature) and (DIM - 1)

    fun signOf(feature: String): Float = if ((fnv1a(feature) ushr 17) and 1 == 1) 1f else -1f

    /**
     * Hashes and L2-normalises. Normalising matters: without it, a long merchant name produces a
     * vector with far more mass than a short one, and the same learning rate would take wildly
     * different-sized steps depending on how verbose the bank's template happens to be.
     */
    fun vectorize(features: List<String>): SparseVector {
        if (features.isEmpty()) return SparseVector(IntArray(0), FloatArray(0))

        val acc = HashMap<Int, Float>(features.size * 2)
        for (f in features) {
            val h = fnv1a(f)
            val idx = h and (DIM - 1)
            val sign = if ((h ushr 17) and 1 == 1) 1f else -1f
            acc[idx] = (acc[idx] ?: 0f) + sign
        }

        val indices = IntArray(acc.size)
        val values = FloatArray(acc.size)
        var n = 0
        var norm = 0f
        for ((k, v) in acc) {
            indices[n] = k
            values[n] = v
            norm += v * v
            n++
        }

        norm = sqrt(norm)
        if (norm > 0f) {
            for (i in values.indices) values[i] /= norm
        }
        return SparseVector(indices, values)
    }
}

/**
 * Turns a transaction into the bag of strings that gets hashed.
 *
 * Character n-grams do the heavy lifting: they generalise across the spelling noise banks inject
 * ("SWIGGY", "Swiggy*8812", "swiggyit") without needing to have seen that exact variant. The
 * contextual features (amount band, time of day, weekday) are what let the model separate merchants
 * whose names say nothing — a ₹40 payment at 9am behaves very differently from ₹4000 at 9pm.
 */
object FeatureExtractor {

    fun extract(
        merchantNorm: String?,
        vpaHandle: String?,
        channel: Channel,
        amountPaise: Long,
        occurredAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val out = ArrayList<String>(72)
        val m = merchantNorm.orEmpty()

        if (m.isNotEmpty()) {
            // Whole-string feature: the strongest signal for a merchant seen before.
            out.add("m=$m")

            val padded = "^$m$"
            for (n in 3..5) {
                if (padded.length < n) continue
                for (i in 0..padded.length - n) out.add("g$n=" + padded.substring(i, i + n))
            }

            for (token in m.split(' ')) {
                if (token.isNotBlank()) out.add("w=$token")
            }
        } else {
            out.add("m=<none>")
        }

        vpaHandle?.let { out.add("h=$it") }
        out.add("c=${channel.name}")
        out.add("a=${amountBucket(amountPaise)}")

        val dt = Instant.ofEpochMilli(occurredAt).atZone(zone)
        out.add("t=${dt.hour / 4}")
        out.add("d=${dt.dayOfWeek.value}")
        out.add("bias=1")

        return out
    }

    /** Log-ish rupee bands. Boundaries picked around everyday Indian spend patterns. */
    private fun amountBucket(paise: Long): Int {
        val rupees = paise / 100.0
        return when {
            rupees < 50 -> 0
            rupees < 150 -> 1
            rupees < 400 -> 2
            rupees < 1_000 -> 3
            rupees < 3_000 -> 4
            rupees < 10_000 -> 5
            rupees < 30_000 -> 6
            else -> 7
        }
    }
}
