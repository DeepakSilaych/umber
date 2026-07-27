package com.deepak.umber.parse

import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regression suite for SMS extraction, built from real Indian bank / UPI templates.
 *
 * Every template here is a shape that actually ships. When a new bank format shows up, add it as a
 * case first — the parser is all heuristics, and this file is the only thing keeping a fix for one
 * bank from quietly breaking another.
 */
class SmsParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /** 12 Jul 2025, 10:00 IST — a fixed "now" so date-drift logic is deterministic. */
    private val receivedAt: Long =
        LocalDateTime.of(2025, 7, 12, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parse(sender: String, body: String) =
        SmsParser.parse(sender, body, receivedAt, zone)

    private fun parsed(sender: String, body: String): ParsedTxn {
        val result = parse(sender, body)
        assertTrue(
            "expected a parse, got $result",
            result is ParseResult.Parsed,
        )
        return (result as ParseResult.Parsed).txn
    }

    private fun rejection(sender: String, body: String): String {
        val result = parse(sender, body)
        assertTrue("expected a rejection, got $result", result is ParseResult.Rejected)
        return (result as ParseResult.Rejected).reason
    }

    // ------------------------------------------------------------------ debits

    @Test
    fun `hdfc upi debit`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Sent Rs.450.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/25 " +
                "Ref 521234567890 Not You? Call 18002586161",
        )

        assertEquals(45_000L, txn.amountPaise)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("1234", txn.accountTail)
        assertEquals("swiggy", txn.merchantNorm)
        assertEquals("521234567890", txn.refNo)
    }

    /**
     * SBI omits the currency symbol entirely ("debited by 120.0"), which is why amount extraction
     * has a verb-anchored bare-number pattern.
     */
    @Test
    fun `sbi upi debit with no currency marker`() {
        val txn = parsed(
            "AD-SBIUPI",
            "Dear UPI user A/C X8912 debited by 120.0 on date 05Jul25 trf to ZEPTO MARKETPLA " +
                "Refno 519612345678. If not u? call 1800111109. -SBI",
        )

        assertEquals(12_000L, txn.amountPaise)
        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals("8912", txn.accountTail)
        assertEquals("zepto marketpla", txn.merchantNorm)
        assertEquals("519612345678", txn.refNo)
        assertEquals(Channel.UPI, txn.channel)
    }

    /**
     * The classic trap: "Credit Card" contains a credit keyword but describes a debit. If this
     * regresses, every card spend gets booked as income and the totals silently collapse.
     */
    @Test
    fun `credit card spend is a debit not a credit`() {
        val txn = parsed(
            "JD-ICICIB",
            "Dear Customer, Your ICICI Bank Credit Card XX1234 has been used for INR 899.00 " +
                "at NETFLIX COM on 05-Jul-25. Avl Limit: INR 1,20,000.00",
        )

        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals(89_900L, txn.amountPaise)
        assertEquals("netflix com", txn.merchantNorm)
        assertEquals("1234", txn.accountTail)
        assertEquals(Channel.CARD, txn.channel)
    }

    @Test
    fun `available limit is read as balance not as the transaction amount`() {
        val txn = parsed(
            "JD-ICICIB",
            "Dear Customer, Your ICICI Bank Credit Card XX1234 has been used for INR 899.00 " +
                "at NETFLIX COM on 05-Jul-25. Avl Limit: INR 1,20,000.00",
        )

        assertEquals(89_900L, txn.amountPaise)
        // ₹1,20,000.00 -> 12,000,000 paise
        assertEquals(12_000_000L, txn.balancePaise)
    }

    // ----------------------------------------------------------------- credits

    @Test
    fun `salary credit`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Dear Customer, your A/c XX6677 has been credited with Rs.85,000.00 on 30-Jun-25 " +
                "towards SALARY. Avl Bal Rs.1,23,456.78",
        )

        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals(85_00_000L, txn.amountPaise)
        assertEquals(1_23_456_78L, txn.balancePaise)
        assertEquals("salary", txn.merchantNorm)
        assertEquals("6677", txn.accountTail)
    }

    /**
     * "debited from ... and credited to ..." names both directions. The one describing the user's
     * own account comes first, which is why direction resolves by earliest keyword.
     */
    @Test
    fun `both directions present resolves to the earlier keyword`() {
        val txn = parsed(
            "AD-SBIUPI",
            "Rs.500.00 debited from A/c XX1234 and credited to merchant@okaxis on 12-07-25. " +
                "UPI Ref 512345678901",
        )

        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals(50_000L, txn.amountPaise)
    }

    // ---------------------------------------------------------------- rejects

    @Test
    fun `otp is rejected`() {
        assertEquals(
            "otp",
            rejection("VM-HDFCBK", "723194 is your OTP for HDFC Bank NetBanking login. Do not share it."),
        )
    }

    @Test
    fun `promotional message is rejected`() {
        assertEquals(
            "promotional",
            rejection("VM-HDFCBK", "Get a pre-approved personal loan of Rs.5,00,000 at 10.5%. Apply now!"),
        )
    }

    @Test
    fun `collect request is rejected because no money has moved`() {
        assertEquals(
            "not a completed txn",
            rejection("AD-SBIUPI", "You have received a collect request of Rs.500.00 from john@ybl on PhonePe."),
        )
    }

    @Test
    fun `future debit notice is rejected`() {
        assertEquals(
            "not a completed txn",
            rejection("VM-HDFCBK", "Rs.1,499.00 will be debited from A/c XX1234 on 15-Jul-25 towards SIP."),
        )
    }

    /** A friend texting about money must never become a ledger entry. */
    @Test
    fun `personal mobile sender is rejected outright`() {
        assertEquals(
            "personal sender",
            rejection("919876543210", "hey I sent you Rs.500 for dinner"),
        )
    }

    /** An unrecognised sender needs an account tail or a reference number to be believable. */
    @Test
    fun `unknown sender without corroborating fields is rejected`() {
        assertEquals(
            "unknown sender, weak signal",
            rejection("AX-XYZABC", "Rs.100.00 debited for your order"),
        )
    }

    @Test
    fun `unknown sender with an account tail is accepted`() {
        val txn = parsed("AX-XYZABC", "Rs.100.00 debited from A/c XX7788 at BIGBASKET")
        assertEquals(10_000L, txn.amountPaise)
        assertEquals("7788", txn.accountTail)
    }

    @Test
    fun `message with no amount is rejected`() {
        assertEquals(
            "no amount",
            rejection("VM-HDFCBK", "Your account statement for June is ready to download."),
        )
    }

    // -------------------------------------------------------------- timestamps

    /**
     * Arrival time wins when it agrees with the in-body date, because it carries a real clock
     * reading that the date alone does not.
     */
    @Test
    fun `same day keeps the sms arrival time`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Sent Rs.450.00 From HDFC Bank A/C x1234 To SWIGGY On 12/07/25 Ref 521234567890",
        )
        assertEquals(receivedAt, txn.occurredAt)
    }

    /** A week of drift means delayed delivery — trust the printed date, anchored at noon. */
    @Test
    fun `stale in-body date overrides the arrival time`() {
        val txn = parsed(
            "AD-SBIUPI",
            "Dear UPI user A/C X8912 debited by 120.0 on date 05Jul25 trf to ZEPTO MARKETPLA " +
                "Refno 519612345678",
        )

        val expected = LocalDateTime.of(2025, 7, 5, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, txn.occurredAt)
    }

    /** A date later than the SMS itself is always a parse artefact. */
    @Test
    fun `future in-body date is ignored`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Sent Rs.450.00 From HDFC Bank A/C x1234 To SWIGGY On 25/12/25 Ref 521234567890",
        )
        assertEquals(receivedAt, txn.occurredAt)
    }

    // ------------------------------------------------------------------ misc

    /**
     * A masked account number sitting directly before the amount must not be read as an amount.
     * "XX1234 Rs.500" trivially satisfies "digits followed by a currency token", and because the
     * account tail comes first in the string it would win the earliest-amount rule.
     */
    @Test
    fun `masked account digits adjacent to the amount are not mistaken for it`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Alert: A/c XX1234 Rs.500.00 debited on 12-07-25. Avl Bal Rs.9,000.00",
        )

        assertEquals(50_000L, txn.amountPaise)
        assertEquals("1234", txn.accountTail)
        assertEquals(9_00_000L, txn.balancePaise)
    }

    @Test
    fun `atm withdrawal is detected as cash channel`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Rs.2,000.00 withdrawn from A/c XX1234 at ATM on 12-07-25. Avl Bal Rs.10,000.00",
        )
        assertEquals(Channel.ATM, txn.channel)
        assertEquals(2_00_000L, txn.amountPaise)
        assertEquals(Direction.DEBIT, txn.direction)
    }

    @Test
    fun `vpa payee yields the local part as the merchant and the psp as the handle`() {
        val txn = parsed(
            "AD-SBIUPI",
            "Rs.250.00 debited from A/c XX1234 trf to vpa bluetokai@okhdfcbank Refno 512345678901",
        )
        assertEquals("bluetokai", txn.merchantNorm)
        assertEquals("okhdfcbank", txn.vpaHandle)
    }

    /**
     * Without the trailer cut, "to block your card" parses as a payee named "block your card" —
     * the boilerplate is grammatically identical to a real payment instruction.
     */
    @Test
    fun `helpline boilerplate is not mistaken for a payee`() {
        val txn = parsed(
            "VM-HDFCBK",
            "Rs.500.00 debited from A/c XX1234. Not you? Call 18001234567 to block your card.",
        )

        assertEquals("", txn.merchantNorm)
        assertNull(txn.merchantRaw)
        assertEquals(50_000L, txn.amountPaise)
    }
}
