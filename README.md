# Umber

An offline Android expense tracker for India. It reads bank and UPI SMS, categorises spending with
a model that trains on your own corrections, and puts rolling 24-hour / 7-day / 30-day totals on
your home screen.

**It has no `INTERNET` permission.** Not a promise in a privacy policy — the app cannot open a
socket, because the permission isn't in the manifest. Your messages, transactions, the model and any
location data physically cannot leave the device.

```
Android 8.0+  ·  Kotlin + Compose  ·  ~5k lines  ·  133 unit tests  ·  MIT
```

---

## Why this exists

Every expense tracker either wants your net-banking credentials, uploads your statements to someone
else's server, or makes you type transactions in by hand. Bank SMS already contains everything
needed. It just has to be parsed well and never leave the phone.

## What it does

- **Reads bank and UPI SMS** — live as they arrive, plus a one-tap import of 1 month / 3 months /
  1 year of history.
- **Imports bank statements** — `.xlsx`, CSV or TSV, with no per-bank configuration.
- **Categorises on-device** — merchant memory, then a logistic-regression model over character
  n-grams, then a bundled lexicon of ~200 Indian merchants for cold start.
- **Learns from you** — one tap in the Review tab writes merchant memory, retro-labels that
  merchant's history and takes a gradient step. The queue shrinks faster than you work through it.
- **Nets reimbursements** — pay a friend ₹1,000 and get ₹1,000 back, and you spent ₹0, not ₹1,000
  of expense plus ₹1,000 of income.
- **Home-screen widget** — rolling spend totals in three responsive sizes.
- **Exports to CSV** — your ledger, in plain numbers that sum in a spreadsheet.

## What it deliberately doesn't do

| | Why |
|---|---|
| No account aggregation | Would require credentials and a server. |
| No cloud sync or backup | Nothing to sync to. Backup is a CSV you control. |
| No PDF statements | Needs a rendering library; every bank offers xlsx/CSV too. |
| No reverse geocoding | Would need network. Locations are coordinates only. |
| No iOS version | iOS gives no app access to SMS content. There is no way to build this. |

---

## Install

Download the APK from [Releases](../../releases) and sideload it. You'll need to allow installs
from unknown sources.

On first launch, grant SMS access (the app has no data source without it) and optionally location.
Then **Settings → Import history → 1 year** to populate the ledger and give the classifier something
to learn from.

> **Play Store:** `READ_SMS` is a restricted permission and Google's permitted-use list does not
> cover expense tracking — this is why apps like Walnut were pulled in 2019. Umber is built for
> sideloading. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#ingestion-sources) for the
> notification-based path that would be needed for Play distribution.

## Build from source

Requires **JDK 17** and the **Android SDK** (API 35).

```bash
git clone https://github.com/DeepakSilaych/umber.git && cd umber
./gradlew testDebugUnitTest
./gradlew installDebug
```

`parse/`, `ml/` and `data/repo/` have no Android imports, so the interesting logic runs as plain JVM
tests without an emulator.

---

## How it works

```
SMS / statement ──▶ filter ──▶ parse ──▶ dedupe ──▶ classify ──▶ enrich ──▶ Room ──▶ widget
                   (drop noise)  (regex)  (3-tier)   (3-layer)   (location)
```

**Parsing is regex, not ML.** Bank SMS are templated, so regex is both more accurate and far easier
to debug — when it's wrong you can see exactly why. ML is reserved for the genuinely fuzzy problem:
merchant → category.

**Classification is three layers**, cheapest and most certain first: exact merchant memory, then an
on-device linear model, then a bundled lexicon. Anything unresolved is flagged for review, and every
review is a training example.

**The model is a multinomial logistic regression** over hashed 3–5 character n-grams. A transformer
would not beat this on two-word merchant strings, and this takes a gradient step in microseconds —
which is what makes "learns from every correction, immediately" possible with no training pipeline
and no server.

Full detail: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**

---

## Contributing

The highest-value contribution is **a bank template that Umber parses badly**. Settings →
"Messages skipped" shows exactly why each message was ignored, which makes these easy to spot.

See **[docs/ADDING-A-BANK.md](docs/ADDING-A-BANK.md)** — the workflow is: add the message shape to
`SmsParserTest` first, watch it fail, then fix the regex.

Please redact account numbers, reference numbers and payee names from any message you paste into an
issue. The test suite uses synthetic values throughout for exactly this reason.

## Licence

MIT — see [LICENSE](LICENSE).

Built by [deepaksilaych](https://github.com/DeepakSilaych).
