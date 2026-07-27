package com.deepak.umber.data.model

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Formatting for amounts held as integer paise.
 *
 * en-IN gives lakh/crore digit grouping (`₹1,23,456`) rather than the thousands grouping a default
 * locale would apply, which is what makes the numbers scan correctly at a glance.
 */
object Money {

    private val LOCALE_IN: Locale = Locale.Builder().setLanguage("en").setRegion("IN").build()

    private val WHOLE: NumberFormat = NumberFormat.getCurrencyInstance(LOCALE_IN).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }

    private val EXACT: NumberFormat = NumberFormat.getCurrencyInstance(LOCALE_IN).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }

    /** Rounded to whole rupees. For totals and the widget, where paise are noise. */
    fun format(paise: Long): String = synchronized(WHOLE) { WHOLE.format((paise / 100.0).roundToLong()) }

    /** Full precision. For a single transaction, where the exact figure is the point. */
    fun formatExact(paise: Long): String = synchronized(EXACT) { EXACT.format(paise / 100.0) }

    /**
     * Short form for tight widget cells: `₹4.2k`, `₹1.3L`, `₹2.1Cr`.
     *
     * Indian magnitude units, not thousands/millions — ₹100000 reads as 1 lakh here, not 100k.
     */
    fun compact(paise: Long): String {
        val rupees = paise / 100.0
        val magnitude = abs(rupees)
        return when {
            magnitude >= 1_00_00_000 -> "₹%.1fCr".format(rupees / 1_00_00_000)
            magnitude >= 1_00_000 -> "₹%.1fL".format(rupees / 1_00_000)
            magnitude >= 1_000 -> "₹%.1fk".format(rupees / 1_000)
            else -> "₹${rupees.roundToLong()}"
        }
    }
}
