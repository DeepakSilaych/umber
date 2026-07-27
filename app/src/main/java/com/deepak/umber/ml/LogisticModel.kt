package com.deepak.umber.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Multinomial logistic regression trained with plain SGD, on-device.
 *
 * Why not something bigger: the input is a 1–4 token merchant string. There is no long-range
 * structure for a transformer to exploit, so a linear model over character n-grams gets you the
 * same accuracy for four orders of magnitude less compute — and, critically, it can take a gradient
 * step in microseconds, which is what makes "learns from every correction, immediately" possible
 * without a training pipeline or a server.
 *
 * Not thread-safe. All access goes through [Classifier], which serialises on a single dispatcher.
 */
class LogisticModel(
    val numClasses: Int,
    val dim: Int = FeatureHasher.DIM,
    val labelsVersion: Int,
) {

    /** Row-major: class c occupies `[c * dim, (c + 1) * dim)`. */
    val weights = FloatArray(numClasses * dim)
    val bias = FloatArray(numClasses)

    var trainedExamples: Int = 0
        private set

    fun scores(v: SparseVector): FloatArray {
        val out = FloatArray(numClasses)
        for (c in 0 until numClasses) {
            var acc = bias[c]
            val off = c * dim
            for (i in 0 until v.size) acc += weights[off + v.indices[i]] * v.values[i]
            out[c] = acc
        }
        return out
    }

    fun predict(v: SparseVector): FloatArray = softmax(scores(v))

    /**
     * One SGD step of cross-entropy with L2 decay.
     *
     * Only touched buckets are updated, so cost scales with the number of active features (~60),
     * not with [dim].
     */
    fun train(v: SparseVector, label: Int, lr: Float = 0.1f, l2: Float = 1e-6f) {
        require(label in 0 until numClasses) { "label $label out of range" }
        if (v.size == 0) return

        val p = predict(v)
        for (c in 0 until numClasses) {
            val grad = p[c] - if (c == label) 1f else 0f
            if (grad == 0f) continue
            val off = c * dim
            for (i in 0 until v.size) {
                val idx = off + v.indices[i]
                weights[idx] -= lr * (grad * v.values[i] + l2 * weights[idx])
            }
            bias[c] -= lr * grad
        }
        trainedExamples++
    }

    fun argmax(p: FloatArray): Int {
        var best = 0
        for (i in p.indices) if (p[i] > p[best]) best = i
        return best
    }

    // ------------------------------------------------------------- persistence

    fun toBytes(): ByteArray {
        val size = HEADER_BYTES + (weights.size + bias.size) * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putInt(FORMAT_VERSION)
        buf.putInt(labelsVersion)
        buf.putInt(numClasses)
        buf.putInt(dim)
        buf.putInt(trainedExamples)
        buf.asFloatBuffer().put(weights).put(bias)
        return buf.array()
    }

    companion object {
        private const val MAGIC = 0x464D444C // "FMDL"
        private const val FORMAT_VERSION = 1
        private const val HEADER_BYTES = 24

        /**
         * Returns null whenever the blob doesn't match what we expect — wrong magic, wrong format,
         * or a label set that has since changed. A silent shape mismatch would produce a model that
         * predicts confidently and wrongly, which is worse than starting over.
         */
        fun fromBytes(bytes: ByteArray?, expectedLabelsVersion: Int): LogisticModel? {
            if (bytes == null || bytes.size < HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            if (buf.int != MAGIC) return null
            if (buf.int != FORMAT_VERSION) return null
            if (buf.int != expectedLabelsVersion) return null

            val numClasses = buf.int
            val dim = buf.int
            val trained = buf.int
            if (numClasses <= 0 || dim <= 0) return null

            val expected = HEADER_BYTES + (numClasses * dim + numClasses) * 4
            if (bytes.size != expected) return null

            val model = LogisticModel(numClasses, dim, expectedLabelsVersion)
            val floats = buf.asFloatBuffer()
            floats.get(model.weights)
            floats.get(model.bias)
            model.trainedExamples = trained
            return model
        }

        fun softmax(scores: FloatArray): FloatArray {
            var max = Float.NEGATIVE_INFINITY
            for (s in scores) if (s > max) max = s

            var sum = 0.0
            val exps = DoubleArray(scores.size)
            for (i in scores.indices) {
                val e = exp((scores[i] - max).toDouble())
                exps[i] = e
                sum += e
            }

            val out = FloatArray(scores.size)
            if (sum <= 0.0) {
                out.fill(1f / scores.size)
                return out
            }
            for (i in out.indices) out[i] = (exps[i] / sum).toFloat()
            return out
        }
    }
}
