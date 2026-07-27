package com.deepak.umber.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LogisticModelTest {

    private fun vec(vararg features: String) = FeatureHasher.vectorize(features.toList())

    private fun model() = LogisticModel(numClasses = 3, labelsVersion = 1)

    @Test
    fun `learns a separable problem`() {
        val m = model()
        val food = vec("m=swiggy", "g3=swi", "g3=wig")
        val transport = vec("m=uber", "g3=ube", "g3=ber")
        val bills = vec("m=airtel", "g3=air", "g3=irt")

        repeat(200) {
            m.train(food, 0, lr = 0.2f)
            m.train(transport, 1, lr = 0.2f)
            m.train(bills, 2, lr = 0.2f)
        }

        assertEquals(0, m.argmax(m.predict(food)))
        assertEquals(1, m.argmax(m.predict(transport)))
        assertEquals(2, m.argmax(m.predict(bills)))
    }

    @Test
    fun `probabilities form a distribution`() {
        val m = model()
        val v = vec("m=swiggy")
        m.train(v, 0, lr = 0.2f)

        val p = m.predict(v)
        assertEquals(3, p.size)
        assertTrue(p.all { it in 0f..1f })
        assertTrue("sum was ${p.sum()}", abs(p.sum() - 1f) < 1e-4f)
    }

    @Test
    fun `an untrained model is maximally uncertain`() {
        val p = model().predict(vec("m=unseen"))
        assertTrue(p.all { abs(it - 1f / 3f) < 1e-4f })
    }

    @Test
    fun `training counts every step`() {
        val m = model()
        repeat(7) { m.train(vec("m=swiggy"), 0, lr = 0.1f) }
        assertEquals(7, m.trainedExamples)
    }

    @Test
    fun `empty vectors are ignored`() {
        val m = model()
        m.train(FeatureHasher.vectorize(emptyList()), 0, lr = 0.1f)
        assertEquals(0, m.trainedExamples)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `serialisation round trips`() {
        val m = model()
        repeat(50) { m.train(vec("m=swiggy", "g3=swi"), 0, lr = 0.2f) }

        val restored = LogisticModel.fromBytes(m.toBytes(), expectedLabelsVersion = 1)
        assertNotNull(restored)
        requireNotNull(restored)

        assertEquals(m.trainedExamples, restored.trainedExamples)
        assertTrue(m.weights.contentEquals(restored.weights))
        assertTrue(m.bias.contentEquals(restored.bias))
        assertEquals(m.argmax(m.predict(vec("m=swiggy"))), restored.argmax(restored.predict(vec("m=swiggy"))))
    }

    /**
     * The important failure mode. If the label set changes, stored weights encode a different
     * meaning per output neuron — loading them anyway yields a model that is confidently wrong,
     * which is far worse than starting from scratch.
     */
    @Test
    fun `weights from a different label set are refused`() {
        val bytes = model().toBytes()
        assertNull(LogisticModel.fromBytes(bytes, expectedLabelsVersion = 2))
    }

    @Test
    fun `truncated or absent blobs are refused`() {
        val bytes = model().toBytes()
        assertNull(LogisticModel.fromBytes(bytes.copyOf(bytes.size - 8), expectedLabelsVersion = 1))
        assertNull(LogisticModel.fromBytes(ByteArray(4), expectedLabelsVersion = 1))
        assertNull(LogisticModel.fromBytes(null, expectedLabelsVersion = 1))
    }

    @Test
    fun `garbage is refused rather than misread`() {
        assertNull(LogisticModel.fromBytes(ByteArray(256) { 0x7F }, expectedLabelsVersion = 1))
    }

    @Test
    fun `softmax is numerically stable for large scores`() {
        val p = LogisticModel.softmax(floatArrayOf(1000f, 999f, -1000f))
        assertTrue(p.all { it.isFinite() })
        assertTrue(abs(p.sum() - 1f) < 1e-4f)
    }
}
