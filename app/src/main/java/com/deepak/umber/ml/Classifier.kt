package com.deepak.umber.ml

import com.deepak.umber.data.db.MerchantMemoryDao
import com.deepak.umber.data.db.MerchantMemoryEntity
import com.deepak.umber.data.db.ModelStateDao
import com.deepak.umber.data.db.ModelStateEntity
import com.deepak.umber.data.db.TxnDao
import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.Categories
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ClassifyInput(
    val merchantNorm: String?,
    val vpaHandle: String?,
    val channel: Channel,
    val direction: Direction,
    val amountPaise: Long,
    val occurredAt: Long,
    val rawBody: String,
)

data class Prediction(
    val category: String,
    val source: CategorySource,
    val confidence: Float,
    val needsReview: Boolean,
)

data class ModelStats(
    val trainedExamples: Int,
    val isTrusted: Boolean,
    val sizeBytes: Int,
)

/**
 * Three-layer categorisation, cheapest and most certain first.
 *
 *  1. **Merchant memory** — exact match on a merchant the user has already categorised. Most
 *     spending is repeat spending, so this resolves the bulk of transactions with certainty.
 *  2. **On-device model** — [LogisticModel] over hashed character n-grams. Handles new merchants
 *     that look like known ones, and merchants whose name is uninformative but whose amount/time
 *     pattern is not.
 *  3. **Seed lexicon** — bundled knowledge, so a fresh install isn't a wall of "Other".
 *
 * Anything not resolved by layers 1 or 2 with sufficient confidence is flagged `needsReview`. That
 * flag is the entire training loop: the review queue collects uncertain rows, the user taps a
 * category, and [learn] folds that correction into both the memory table and the model weights
 * immediately.
 *
 * All state mutation is serialised behind [mutex] — [LogisticModel] is not thread-safe and ingest
 * can fire from a broadcast receiver while the UI is writing a correction.
 */
class Classifier(
    private val memoryDao: MerchantMemoryDao,
    private val modelDao: ModelStateDao,
    private val txnDao: TxnDao,
) {

    private val mutex = Mutex()
    private var model: LogisticModel? = null

    private suspend fun requireModel(): LogisticModel {
        model?.let { return it }
        val stored = modelDao.get()
        val loaded = LogisticModel.fromBytes(stored?.weights, Categories.LABELS_VERSION)
            ?: LogisticModel(Categories.COUNT, labelsVersion = Categories.LABELS_VERSION)
        model = loaded
        return loaded
    }

    private suspend fun persist(m: LogisticModel) {
        modelDao.put(
            ModelStateEntity(
                id = 0,
                labelsVersion = Categories.LABELS_VERSION,
                trainedExamples = m.trainedExamples,
                weights = m.toBytes(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun classify(input: ClassifyInput): Prediction = mutex.withLock {
        val merchant = input.merchantNorm?.takeIf { it.isNotBlank() }

        // ---- Layer 1: exact memory
        if (merchant != null) {
            memoryDao.get(merchant)?.let { remembered ->
                return@withLock Prediction(
                    category = remembered.category,
                    source = CategorySource.MEMORY,
                    confidence = 1f,
                    needsReview = false,
                )
            }
        }

        // ---- Layer 2: on-device model
        val m = requireModel()
        if (m.trainedExamples >= MIN_EXAMPLES_TO_TRUST) {
            val vector = FeatureHasher.vectorize(featuresOf(input))
            val probs = m.predict(vector)
            val best = m.argmax(probs)
            val p = probs[best]
            if (p >= ACCEPT_THRESHOLD) {
                return@withLock Prediction(
                    category = Categories.labelAt(best),
                    source = CategorySource.MODEL,
                    confidence = p,
                    needsReview = false,
                )
            }
        }

        // ---- Layer 3: seed lexicon
        val seeded = SeedLexicon.lookup(merchant) ?: SeedLexicon.lookupInBody(input.rawBody)
        if (seeded != null) {
            return@withLock Prediction(
                category = seeded,
                source = CategorySource.SEED,
                confidence = SEED_CONFIDENCE,
                // Seeded guesses are asked about once. Confirming turns them into memory + a
                // training example; that is how the model gets off the ground.
                needsReview = input.direction == Direction.DEBIT,
            )
        }

        // ---- Nothing matched.
        val fallback = if (input.direction == Direction.CREDIT) Categories.INCOME else Categories.OTHER
        return@withLock Prediction(
            category = fallback,
            source = CategorySource.NONE,
            confidence = 0f,
            // Credits don't move the spend totals the widget shows, so leaving them unreviewed
            // keeps the review queue focused on what actually matters.
            needsReview = input.direction == Direction.DEBIT,
        )
    }

    /**
     * Folds a user correction into both layers.
     *
     * The replay pass is the important part. Training only on the newest example makes the model
     * chase whatever the user last touched — a run of five corrections in one category would skew
     * every subsequent prediction toward it. Interleaving a random sample of past confirmed
     * examples keeps the decision boundary anchored to the whole history.
     */
    suspend fun learn(txn: TxnEntity, category: String): Unit = mutex.withLock {
        if (!Categories.isValid(category)) return@withLock

        val merchant = txn.merchantNorm?.takeIf { it.isNotBlank() }
        if (merchant != null) {
            val existing = memoryDao.get(merchant)
            memoryDao.put(
                MerchantMemoryEntity(
                    merchantNorm = merchant,
                    category = category,
                    confirmations = (existing?.confirmations ?: 0) + 1,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        val m = requireModel()
        val label = Categories.indexOf(category)
        if (label < 0) return@withLock

        val vector = FeatureHasher.vectorize(featuresOf(txn))
        repeat(FRESH_EPOCHS) { m.train(vector, label, lr = FRESH_LR) }

        for (past in txnDao.randomConfirmed(REPLAY_SIZE)) {
            val pastLabel = Categories.indexOf(past.category)
            if (pastLabel < 0 || past.id == txn.id) continue
            m.train(FeatureHasher.vectorize(featuresOf(past)), pastLabel, lr = REPLAY_LR)
        }

        persist(m)
    }

    /**
     * Rebuilds the model from every user-confirmed transaction.
     *
     * Needed when [Categories.LABELS_VERSION] changes (the weight matrix shape is now wrong) and
     * useful as a manual "the model has gone weird" escape hatch. Cheap enough to run in the
     * foreground — a few thousand examples is well under a second.
     */
    suspend fun retrainFromScratch(): ModelStats = mutex.withLock {
        val fresh = LogisticModel(Categories.COUNT, labelsVersion = Categories.LABELS_VERSION)
        val examples = txnDao.allConfirmed()
            .mapNotNull { txn ->
                val label = Categories.indexOf(txn.category)
                if (label < 0) null else FeatureHasher.vectorize(featuresOf(txn)) to label
            }

        repeat(RETRAIN_EPOCHS) {
            for ((vector, label) in examples.shuffled()) {
                fresh.train(vector, label, lr = RETRAIN_LR)
            }
        }

        model = fresh
        persist(fresh)
        ModelStats(fresh.trainedExamples, fresh.trainedExamples >= MIN_EXAMPLES_TO_TRUST, fresh.toBytes().size)
    }

    suspend fun stats(): ModelStats = mutex.withLock {
        val m = requireModel()
        ModelStats(m.trainedExamples, m.trainedExamples >= MIN_EXAMPLES_TO_TRUST, m.weights.size * 4)
    }

    private fun featuresOf(input: ClassifyInput): List<String> = FeatureExtractor.extract(
        merchantNorm = input.merchantNorm,
        vpaHandle = input.vpaHandle,
        channel = input.channel,
        amountPaise = input.amountPaise,
        occurredAt = input.occurredAt,
    )

    private fun featuresOf(txn: TxnEntity): List<String> = FeatureExtractor.extract(
        merchantNorm = txn.merchantNorm,
        vpaHandle = txn.vpaHandle,
        channel = txn.channel,
        amountPaise = txn.amountPaise,
        occurredAt = txn.occurredAt,
    )

    companion object {
        /**
         * Below this, the model has seen too little to beat the seed lexicon, so it is not
         * consulted at all. Trusting a barely-trained softmax produces confident nonsense.
         */
        const val MIN_EXAMPLES_TO_TRUST = 25

        /** Tuned to prefer "ask the user" over "guess wrong and hide it". */
        const val ACCEPT_THRESHOLD = 0.62f

        const val SEED_CONFIDENCE = 0.45f

        private const val FRESH_EPOCHS = 5
        private const val FRESH_LR = 0.15f
        private const val REPLAY_SIZE = 48
        private const val REPLAY_LR = 0.05f
        private const val RETRAIN_EPOCHS = 12
        private const val RETRAIN_LR = 0.08f
    }
}
