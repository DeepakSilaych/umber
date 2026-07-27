# Adding a bank template

The most useful contribution to Umber is a message shape it currently gets wrong. This is how to fix
one.

---

## 1. Find out what went wrong

**Settings → Messages skipped** shows why each non-transaction message was ignored, grouped by
reason. The reasons map directly to checks in `SmsParser`:

| Reason | Meaning |
|---|---|
| `no amount` | No currency figure found. Usually correct — flight itineraries, OTP-free notices. |
| `no direction keyword` | Nothing said debited/credited/spent/paid. Often a real miss. |
| `otp` / `promotional` | Correctly filtered. |
| `not a completed txn` | Future tense, a payment request, or a failure. |
| `duplicate txn` | Deduplicated against an existing row. |
| `unknown sender, weak signal` | Unrecognised sender with no account tail and no reference. |
| `personal sender` | Sender looked like a personal mobile number. |

`no direction keyword` and `no amount` are where genuine misses hide. A large `duplicate txn` count
relative to real transactions is a red flag — it usually means a reference number is being
mis-extracted and collapsing distinct payments.

## 2. Write the test first

Add the message to `RealTemplatesTest` and watch it fail before touching any regex. Every case in
that file corresponds to something the parser once got wrong on live data.

```kotlin
@Test
fun `some bank upi debit`() {
    val txn = parsed(
        "VM-EXAMPL",
        "Rs 250.00 debited from Example Bank A/c XX1234 on 08-05-2026 " +
            "towards MERCHANT NAME. Ref 512345678901",
    )

    assertEquals(25_000L, txn.amountPaise)          // paise, always
    assertEquals(Direction.DEBIT, txn.direction)
    assertEquals("1234", txn.accountTail)
    assertEquals("merchant name", txn.merchantNorm) // normalised, lowercase
    assertEquals("512345678901", txn.refNo)
}
```

> **Redact first.** Replace real account tails, reference numbers and payee names with synthetic
> values of the same *shape*. The parser reacts to structure, never to the values, so a sanitised
> message tests exactly as well as a real one.

Run just the parser tests:

```bash
./gradlew testDebugUnitTest --tests '*SmsParserTest' --tests '*RealTemplatesTest'
```

## 3. Fix the extractor

Everything lives in `parse/SmsParser.kt`. In rough order of how often each needs touching:

**Merchant** — `MERCHANT_PATTERNS`, an ordered list where the first plausible hit wins. Specific
templates go before generic prepositions. If a capture matches structurally but is meaningless (a
bare amount, the user's own account), extend `isPlausibleMerchant` rather than contorting the regex.

`NAME_END` is the shared terminator that stops a name swallowing the rest of the sentence — adding a
word there is often the whole fix.

**Amount** — three patterns: currency-prefixed, currency-suffixed, and verb-anchored bare numbers
(`debited by 120.0`). Balances are distinguished positionally by `BALANCE_CONTEXT` testing the text
immediately before the digits.

**Direction** — `DEBIT_WORDS` / `CREDIT_WORDS`, resolved by *earliest* match. Add specific phrases,
not general ones: `recharge of` rather than `recharge`, because marketing SMS say "Recharge now".

**Rejections** — `REJECT_OTP`, `REJECT_PROMO`, `REJECT_NOT_YET`, `REJECT_INFO_ONLY`. Prefer a
rejection over a bad parse; a missing transaction is visible in Settings, a wrong one is not.

**Sender** — `SenderFilter.BANK_CODES` / `PSP_CODES` take DLT entity codes (the six characters in
`VM-HDFCBK`).

## 4. Bump the parser version

If extraction changed, increment `SmsParser.VERSION`. That is what lets **Re-scan** replay messages
the old version rejected, and it is easy to forget.

Existing *successfully parsed* rows keep their old extraction — re-parsing them would delete and
recreate their transactions, discarding any category the user had corrected. **Rebuild ledger** is
the deliberate, user-initiated escape hatch for that.

---

## Statement columns

Bank statements need no code change for new column names — extend the keyword lists in
`io/StatementParser.kt`:

```kotlin
private val DEBIT_KEYS = listOf("withdrawal amt", "withdrawal amount", "debit amount", ...)
```

Order matters. Exact matches are tried before substring matches, so `withdrawal amount` is never
beaten by a substring hit on `amount`. Add specific variants first.

`StatementParser` is pure and takes a `List<List<String>>`, so tests construct the grid directly
without a file. `XlsxReaderTest` shows how to build a real `.xlsx` in memory when you need to
exercise the zip and XML layers too.

---

## House rules

- **Money is `Long` paise.** Never `Double`, never rupees.
- **`parse/`, `ml/`, `io/` stay Android-free.** That is what keeps them testable on the JVM.
- **Comments explain *why*, not *what*.** The regexes are dense; a note about the failure mode a
  pattern prevents is worth more than a restatement of the syntax.
- **Prefer a rejection to a guess.** Everything unresolved surfaces in the Review queue and becomes
  a training example. A confident wrong answer does not.
