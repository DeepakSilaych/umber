# Architecture

How a bank SMS becomes a categorised, deduplicated row in a ledger that never leaves the phone.

---

## Layout

```
parse/       Pure JVM. SmsParser, SenderFilter, Normalize, DateParse, MessageFingerprint.
ml/          Pure JVM. FeatureHasher, LogisticModel, SeedLexicon + the Classifier orchestrator.
io/          Pure JVM. Csv, StatementParser, XlsxReader, LedgerCsv.
data/db/     Room entities, DAOs, converters.
data/repo/   UmberRepository (windows, series, writes) and Netting.
ingest/      IngestPipeline, SmsReceiver (live), SmsBackfill (history).
location/    LocationCache — foreground-only fixes with age confidence.
widget/      Glance widget + updater.
work/        RollupWorker.
ui/          Compose: Home, History, Review, Settings.
```

Everything under `parse/`, `ml/`, `io/` and the `Netting` object is free of Android imports. That is
deliberate: it is where all the fiddly logic lives, and it means 133 tests run on the JVM in seconds
without an emulator.

Dependency injection is a plain `AppContainer` field on the `Application`. The graph is a dozen
objects with no scoping beyond "application", and it has to be reachable from a `BroadcastReceiver`
and a Glance widget that only hold a `Context` — a DI framework would add build cost and indirection
for nothing.

---

## The pipeline

```
observation ──▶ sender filter ──▶ parse ──▶ dedupe ──▶ classify ──▶ enrich ──▶ persist ──▶ widget
```

Live SMS, historical backfill, statement imports and CSV restores all funnel through
`IngestPipeline`, so deduplication and classification behave identically regardless of origin. That
matters most in the case users actually hit: importing a statement that overlaps months of SMS
already captured.

### Ingestion sources

`SourceType` distinguishes `SMS`, `NOTIFICATION`, `STATEMENT`, `LEDGER_CSV` and `MANUAL`. Only the
SMS path is implemented.

The notification path exists in the type system because it is what Play Store distribution would
require: `READ_SMS` is a restricted permission that expense trackers do not qualify for, whereas a
`NotificationListenerService` can read the same bank message from the notification the SMS app posts,
plus native notifications from GPay/PhonePe/Paytm.

### Raw messages are the source of truth

Every message is stored verbatim in `raw_message` with the `parserVersion` that processed it. The
extracted transaction is derived data.

This is what makes parser improvements retroactive. Bumping `SmsParser.VERSION` lets **Re-scan**
replay everything an older version rejected, and **Rebuild ledger** re-derives every transaction
from scratch. Without stored raw text, every extraction bug would be permanent — the original SMS
may be long gone from the device.

Rebuild is less destructive than it sounds: `merchant_memory` and the model live in separate tables,
so confirmed categories are re-applied automatically by the classifier's first layer as the rebuild
proceeds.

---

## Parsing

Regex, not ML. Bank SMS are templated, so regex is more accurate *and* debuggable; when it is wrong
you can point at the line. See [ADDING-A-BANK.md](ADDING-A-BANK.md) for the extraction rules.

Things that are easy to get wrong and are pinned by tests:

- **"Credit Card" is not a credit.** Direction resolves by *earliest* keyword after masking
  `credit card` / `debit card` / `credit limit`. Without this, every card spend books as income.
- **Balances aren't amounts.** An amount preceded by `Avl Bal` / `Avl Limit` is tagged positionally,
  so template ordering doesn't matter.
- **Masked account digits aren't amounts.** `A/c XX1234 Rs.500.00` trivially matches
  "digits followed by a currency token", and the tail comes first in the string.
- **Some banks omit the currency symbol** — SBI writes `debited by 120.0`.
- **Negations are not transactions.** `Your account is not debited with Rs 500.00 ... due to cbs
  rejection` is word-for-word a successful debit apart from the negation.
- **Future tense is not a transaction.** `will be debited`, `will be transferred`.
- **Helpline boilerplate looks like a payment.** "Call 1800… **to block your card**" parses as a
  payee named "block your card" unless the trailer is cut first.
- **Your own account is never a payee.** "transferred to your Savings Account XXX468" matches the
  generic `to <name>` pattern perfectly.
- **Friends aren't banks.** Personal mobile senders are rejected before anything is stored.

### Statement imports

An `.xlsx` is a zip of XML, so `XlsxReader` is `ZipInputStream` plus SAX — both in the platform.
Apache POI would add megabytes to an app whose pitch is staying small and local. Format is detected
from **magic bytes**, not filename or MIME type, because banks routinely serve a CSV named `.xls`.

There are no per-bank templates. Every Indian bank invents its own column names, pads the top of the
file with account preamble, and disagrees about whether debit and credit are two columns or one
signed one. So the header row is located by scoring, and columns are matched on keywords.

---

## Deduplication

Three tiers, chosen by what the source actually knows.

**1 — Reference number.** Authoritative in both directions. A match means duplicate; an *unseen*
reference is positive proof of a distinct transaction and short-circuits the fuzzy checks entirely.
This is what makes statement-versus-SMS reconciliation reliable, since both carry the same UPI RRN.
References are normalised to bare uppercase alphanumerics so `UPI:512345678902` and
`UPI/512345678902/PAYMENT` collapse to one key.

A reference must contain at least four digits to be used. This is not a detail — the phrase
"UPI Mandate" once caused the extractor to capture the word `MANDATE` as a reference, and because a
reference doubles as a dedupe key, every recurring mandate payment after the first was silently
discarded as a duplicate of it.

**2 — Time window** (live sources). One payment routinely produces both a bank SMS and a payment-app
notification, seconds apart. Half-width is three minutes.

**3 — Same-day occurrence count** (date-only sources). A statement row's timestamp is a fabricated
midnight, so a minutes-wide window is meaningless. Instead the Nth identical row is skipped only when
at least N such transactions already exist. This keeps nine identical ₹500 mandate debits as nine
transactions, makes re-importing a file a clean no-op, and tops up exactly the difference when SMS
captured only some of them.

---

## Classification

| Layer | Mechanism | Confidence |
|---|---|---|
| 1 | **Merchant memory** — exact match on a user-confirmed merchant | certain |
| 2 | **On-device model** — logistic regression over hashed n-grams | thresholded at 0.62 |
| 3 | **Seed lexicon** — ~200 bundled Indian merchants | low, always reviewed |

Layer 1 does most of the work, because spending is dominated by repeat merchants.

**The model.** Multinomial logistic regression trained with plain SGD over 3–5 character n-grams of
the merchant string, plus amount band, hour-of-day, weekday, channel and VPA handle. Character
n-grams generalise across the spelling noise banks inject (`SWIGGY`, `Swiggy*8812`, `swiggyit`)
without having seen that exact variant.

Features are hashed into 2^14 buckets — merchant vocabulary is open-ended, so a fixed vocabulary
would need constant rebuilding. Signed hashing makes collisions cancel in expectation rather than
compound. Vectors are L2-normalised so a verbose bank template doesn't take a larger gradient step
than a terse one.

Below 25 training examples the model is not consulted at all; a barely-trained softmax produces
confident nonsense.

**Cost, honestly:** the weight matrix is dense — 15 classes × 16384 buckets × 4 bytes ≈ **1 MB**,
rewritten on each correction. Fine on any modern phone, but it is a megabyte, not the tens of
kilobytes a sparse design would use.

**The training loop.** Anything unresolved is flagged `needsReview`. Confirming writes merchant
memory, retro-labels that merchant's history, and takes a gradient step. Corrections replay against a
random sample of past confirmations, so a run of edits in one category doesn't skew the boundary.

`Categories.ALL` order *is* the model's label encoding. Any edit must bump `LABELS_VERSION`, which
discards the stored weights and retrains from confirmed history — a silent shape mismatch would
produce a model that is confidently and invisibly wrong.

---

## Reimbursement netting

Paying a friend ₹1,000 and being paid back ₹1,000 is not ₹1,000 of expense plus ₹1,000 of income. It
is ₹0. Counting both inflates spending *and* fabricates income, and the distortion compounds with
every split bill.

`Netting` offsets credits against debits **per counterparty**:

- A credit only offsets spending from a counterparty you actually paid in the same window, and never
  by more than you paid them. Money from someone you never paid is income, not a refund.
- **Salary can never offset spending.** The counterparty match does most of the work — a paycheque
  can only offset money paid *to* that employer, which nobody does. Credits the user has
  deliberately filed as Income are dropped on top of that.
- Deliberately *not* filtered: credits still sitting at the default `Income` category. Unclassified
  credits default to Income, so excluding them all would mean a friend's repayment could never net.
- A category can be driven to zero but never negative.

**Known limitation:** matching is by normalised name, so a bank writing the same person two ways
("DEEPAK SILAYCH" vs "DEEPAKSILAYCH") splits them into counterparties that never net. Honorifics are
stripped; missing-space variants are not handled.

---

## Location, without the scary permission

Ingest happens in a `BroadcastReceiver`, and on Android 10+ a background process cannot read location
without `ACCESS_BACKGROUND_LOCATION` — a permission with a heavy consent flow and no real
justification here.

So instead: while the app is in the foreground, `LocationCache` subscribes to the passive and network
providers and persists each fix. At ingest, that cached fix is attached with an **age-derived
confidence** (HIGH ≤ 2 min, MEDIUM ≤ 10 min, LOW ≤ 1 hr). Only HIGH is surfaced in the UI. No
background location permission is ever requested.

Be realistic about what this buys: accurate for card swipes and in-store UPI, meaningless for an
online purchase made at home, which correctly but uninterestingly geotags as home.

---

## The widget

Jetpack Glance, three responsive sizes. Windows are **rolling**, so they go stale on their own:
`RollupWorker` runs every 20 minutes purely to move the boundaries forward, and ingest pushes an
update the moment a transaction lands.

The sparkline is bucketed to 10 bars rather than 30. Glance renders through `RemoteViews`, where a
container with 30 children is both fragile and unreadable at widget width.

Glance 1.1 has no day/night `ColorProvider`, so the widget palette lives in `values/` and
`values-night/` colour resources and is resolved by the platform at render time.

---

## Data model notes

- **Money is integer paise, everywhere.** Never floats — a rounding drift of a hundredth of a rupee
  per row silently corrupts every total.
- **Enums are stored by name, not ordinal.** Ordinals would silently remap every existing row the
  first time a value is inserted in the middle of an enum.
- **No destructive migration fallback.** Everything derived can be rebuilt from `raw_message`;
  `raw_message` itself cannot be recovered.
- **Backups are disabled** in `data_extraction_rules.xml`. The database holds raw bank SMS; syncing
  that to a cloud backup transport would undo the entire privacy posture.
