package com.deepak.umber.parse

import com.deepak.umber.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Templates captured from a real device inbox (ICICI, SBI Card, IDFC FIRST, Airtel).
 *
 * Every case here corresponds to something the first version of the parser got wrong on live data.
 * Personal identifiers have been replaced with placeholders; the message *structure* — which is the
 * only thing the parser reacts to — is untouched.
 */
class RealTemplatesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val receivedAt: Long =
        LocalDateTime.of(2026, 5, 8, 14, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parsed(sender: String, body: String): ParsedTxn {
        val result = SmsParser.parse(sender, body, receivedAt, zone)
        assertTrue("expected a parse, got $result", result is ParseResult.Parsed)
        return (result as ParseResult.Parsed).txn
    }

    private fun rejection(sender: String, body: String): String {
        val result = SmsParser.parse(sender, body, receivedAt, zone)
        assertTrue("expected a rejection, got $result", result is ParseResult.Rejected)
        return (result as ParseResult.Rejected).reason
    }

    /**
     * The costliest bug found on real data.
     *
     * "UPI Mandate" made the reference extractor capture the word "Mandate". Because refNo is a
     * uniquely-indexed dedupe key, the first mandate payment claimed it and every later one — nine
     * separate ₹500 debits on different days — was discarded as a duplicate.
     */
    @Test
    fun `upi mandate does not hijack the reference number`() {
        val txn = parsed(
            "AD-ICICIT-S",
            "Rs 500.00 debited from ICICI Bank Savings Account XX9012 on 02-May-26 towards ARJUN MEHTA " +
                "for UPI Mandate AutoPay Retrieval Ref No.512345678901",
        )

        assertEquals("512345678901", txn.refNo)
        assertEquals("arjun mehta", txn.merchantNorm)
        assertEquals(50_000L, txn.amountPaise)
        assertEquals("9012", txn.accountTail)
    }

    /** A bare "UPI:<rrn>" is still a valid reference — the fix is validation, not a narrower trigger. */
    @Test
    fun `bare upi prefix still yields the reference`() {
        val txn = parsed(
            "AD-ICICIT-S",
            "ICICI Bank Acct XX9012 debited for Rs 99.00 on 08-May-26; VENDCO credited. " +
                "UPI:512345678902. Call 18002662 for dispute.",
        )

        assertEquals("512345678902", txn.refNo)
        // Previously captured "Rs 99.00" as the payee, via the generic "for <name>" pattern.
        assertEquals("vendco", txn.merchantNorm)
    }

    /** A failed mandate is word-for-word a successful debit apart from the negation. */
    @Test
    fun `negated debit is rejected`() {
        assertEquals(
            "not a completed txn",
            rejection(
                "AD-ICICIT-S",
                "Your account is not debited with Rs 500.00 towards ARJUN MEHTA for UPI Mandate " +
                    "due to cbs rejection 0116, RRN 512345678905-ICICI Bank.",
            ),
        )
    }

    /** Card-network descriptor: the merchant is a bare token with no preposition to anchor on. */
    @Test
    fun `scheme prefixed merchant descriptor is extracted`() {
        val txn = parsed(
            "AD-ICICIT-S",
            "Rs. 59.00 debited from ICICI Bank Acc XX9012 on 06-May-26 VSI*YOUTUBEGO. " +
                "Bal Rs. 863.13. If not you call 18002662",
        )

        assertEquals("youtubego", txn.merchantNorm)
        assertEquals(5_900L, txn.amountPaise)
        assertEquals(86_313L, txn.balancePaise)
    }

    /** "from <name>" names the payer on a credit — and the user's own account on a debit. */
    @Test
    fun `incoming transfer takes the payer as the merchant`() {
        val txn = parsed(
            "AD-ICICIT-S",
            "Dear Customer, Acct XX9012 is credited with Rs 10000.00 on 07-May-26 from RAVI KUMAR. " +
                "UPI:512345678903-ICICI Bank.",
        )

        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals("ravi kumar", txn.merchantNorm)
        assertEquals(10_00_000L, txn.amountPaise)
    }

    @Test
    fun `outgoing debit does not take the users own bank as the merchant`() {
        val txn = parsed(
            "AD-ICICIT-S",
            "Rs 500.00 debited from ICICI Bank Savings Account XX9012 on 02-May-26 towards ARJUN MEHTA " +
                "for UPI Mandate AutoPay Retrieval Ref No.512345678901",
        )

        assertTrue(
            "merchant should not be the user's own bank, was '${txn.merchantNorm}'",
            !txn.merchantNorm.contains("icici"),
        )
    }

    @Test
    fun `standing instruction payment strips the merchant label`() {
        val txn = parsed(
            "JD-ICICIT-S",
            "We have successfully processed payment of INR 59.00 to Merchant Youtube, as per " +
                "Standing Instruction SI9X8W7V6U on 06/05/2026 for ICICI Bank Debit Card 7788.",
        )

        assertEquals("youtube", txn.merchantNorm)
        assertEquals(Direction.DEBIT, txn.direction)
    }

    @Test
    fun `credit card bill payment names the instrument`() {
        val txn = parsed(
            "VM-SBICRD",
            "Dear SBI Cardholder, payment of Rs. 8857.32 for your SBI Credit Card has been " +
                "successfully processed. ref no : ABC12XY34ZQ56.",
        )

        assertEquals("sbi credit card", txn.merchantNorm)
        assertEquals(8_85_732L, txn.amountPaise)
        assertEquals("ABC12XY34ZQ56", txn.refNo)
    }

    /** Telecom top-ups report success without ever saying "debited". */
    @Test
    fun `recharge success is a debit`() {
        val txn = parsed(
            "Airtel-S",
            "Recharge of INR 100.00 is successful for your Airtel Mobile on 08-05-2026 02:21, " +
                "Transaction ID 700123456.",
        )

        assertEquals(Direction.DEBIT, txn.direction)
        assertEquals(10_000L, txn.amountPaise)
        assertEquals("airtel mobile", txn.merchantNorm)
    }

    /**
     * The counterpart risk: marketing SMS advertising a top-up must not become a spend. This is
     * why the debit phrases are "recharge of" / "topup of" rather than bare "recharge" / "topup".
     */
    @Test
    fun `recharge advertisement is not a debit`() {
        val reason = rejection(
            "AR-AIRTEL-S",
            "Alert!50%-: of daily high speed data is consumed. Get 12GB data topup at just Rs161 " +
                "| valid for 30 days | Recharge now i.airtel.in/dtpck",
        )
        assertTrue("unexpected reason: $reason", reason != "parsed")
    }

    @Test
    fun `interest credit is recognised`() {
        val txn = parsed(
            "JK-IDFCFB-S",
            "Monthly interest of INR.3,738.00 earned on your Savings A/c XX3344 has been credited " +
                "to your A/C on 30/04/26. New bal: INR.9,27,610.90. IDFC FIRST Bank",
        )

        assertEquals(Direction.CREDIT, txn.direction)
        assertEquals(3_73_800L, txn.amountPaise)
        assertEquals("3344", txn.accountTail)
    }

    /**
     * "will be transferred" is future tense — nothing has moved. The original rule only covered
     * debited/credited/charged, so this slipped through and booked a 27-paise transaction whose
     * payee was the user's own savings account.
     */
    @Test
    fun `future transfer notice is rejected`() {
        assertEquals(
            "not a completed txn",
            rejection(
                "AD-ICICIT-S",
                "UPI LITE has been disabled. An amount of Rs 0.27 will be transferred to your " +
                    "Savings Account XXX9012. Your ref no. is 512345678904-ICICI Bank.",
            ),
        )
    }

    /** The user's own account matches the generic "to <name>" pattern, but is never a payee. */
    @Test
    fun `own account is never taken as the merchant`() {
        val txn = parsed(
            "AD-ICICIT-S",
            "Rs 250.00 has been transferred to your Savings Account XXX9012 on 08-05-2026. " +
                "Ref no. 512345678907",
        )
        assertEquals("", txn.merchantNorm)
    }

    /** Non-financial messages from bank senders must stay out of the ledger. */
    @Test
    fun `device registration and statement notices are skipped`() {
        assertNotNull(
            rejection(
                "JK-IDFCFB-S",
                "Your new device has been registered for Mobile Banking on 03/05/2026 at 17:34. " +
                    "(PIXEL-X, android) Not you? Call 1800 10 888. Team IDFC FIRST Bank",
            ),
        )
        assertNotNull(
            rejection(
                "JD-ICICIT-S",
                "The statement for ICICI Bank Acc XX9012 from 01-Apr-26 to 30-Apr-26 is generated. " +
                    "Download at https://icici.co/x .Valid for 7 days",
            ),
        )
    }
}
