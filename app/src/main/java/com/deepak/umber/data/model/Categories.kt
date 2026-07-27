package com.deepak.umber.data.model

/**
 * The fixed label set for the classifier.
 *
 * [ALL] order IS the model's label encoding — index N in this list is output neuron N. Changing the
 * order or removing an entry silently corrupts every stored weight matrix, so any edit here MUST
 * bump [LABELS_VERSION], which forces the model to be discarded and retrained from confirmed
 * history on next launch.
 *
 * Appending to the end is also a label-set change (the output layer widens), so bump for that too.
 */
object Categories {

    const val LABELS_VERSION = 1

    const val FOOD = "Food & Dining"
    const val GROCERIES = "Groceries"
    const val TRANSPORT = "Transport"
    const val SHOPPING = "Shopping"
    const val BILLS = "Bills & Utilities"
    const val ENTERTAINMENT = "Entertainment"
    const val HEALTH = "Health"
    const val EDUCATION = "Education"
    const val RENT = "Rent & Housing"
    const val INVESTMENTS = "Investments"
    const val TRANSFERS = "Transfers"
    const val CASH = "Cash"
    const val TRAVEL = "Travel"
    const val INCOME = "Income"
    const val OTHER = "Other"

    val ALL: List<String> = listOf(
        FOOD,
        GROCERIES,
        TRANSPORT,
        SHOPPING,
        BILLS,
        ENTERTAINMENT,
        HEALTH,
        EDUCATION,
        RENT,
        INVESTMENTS,
        TRANSFERS,
        CASH,
        TRAVEL,
        INCOME,
        OTHER,
    )

    val COUNT: Int = ALL.size

    fun indexOf(category: String): Int = ALL.indexOf(category)

    fun labelAt(index: Int): String = ALL.getOrElse(index) { OTHER }

    fun isValid(category: String): Boolean = ALL.contains(category)
}
