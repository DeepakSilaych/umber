# Sync protocol

How the `cloud` flavour talks to the Umber server. This is the contract both sides implement; read
it before changing either.

Not relevant to the `privacy` flavour, which has no `INTERNET` permission and cannot participate.

---

## What crosses the wire

**The parsed ledger only.** Amount, direction, merchant, category, account tail, reference number,
timestamp.

**Raw SMS never leaves the phone.** Account numbers, balances and the verbatim bank message stay in
`raw_message` locally. The server cannot re-parse, and that is the deliberate trade: extraction
improvements ship in the app, not centrally.

Location is also excluded. It is inferred, low-confidence, and adds nothing the dashboard needs.

## Shape

```
phone (source of truth for ingest)  ⇄  server (source of truth for enrichment)
        Room `txn`                          Postgres `umber.transactions`
```

The phone owns *observation*: it reads SMS, parses, dedupes and classifies locally. It works
completely offline and always has.

The server owns *enrichment*: LLM classification of merchants the phone couldn't resolve, plus
dashboard edits. It never invents transactions.

Two-way, but not symmetric — see conflict rules.

---

## Identity

A transaction's cross-device identity is its **`client_id`**, a UUIDv4 the phone generates on
insert and never changes. Not the Room row id, which is local and would collide across installs.

Deduplication still happens on the phone before a row is ever created (reference number, then time
window, then same-day occurrence count — see [ARCHITECTURE.md](ARCHITECTURE.md#deduplication)). The
server does not re-run that logic; it trusts what it is given and rejects duplicate `client_id`s.

## Conflict rules

Both sides can change a category. Only one rule matters:

> **A user's own correction always wins.**

Concretely, in descending priority:

| Origin | `category_source` | Beats |
|---|---|---|
| User tapped a category in the app | `USER` | everything |
| User edited in the dashboard | `DASHBOARD` | everything except a later `USER` |
| Server LLM classification | `REMOTE` | `MODEL`, `SEED`, `NONE` |
| On-device model / lexicon | `MODEL`, `SEED`, `NONE` | nothing |

Ties within a tier break by `updated_at`, last write wins. The phone never overwrites a `USER` row
with a `REMOTE` one, which is the failure mode that would make the feature actively harmful — a
model silently undoing a decision the user made by hand.

`updated_at` is set by whoever made the change, in UTC milliseconds.

---

## Endpoints

Base: `https://umber.deepaksilaych.me`
Auth: `Authorization: Bearer <device-token>` on everything.

### `POST /v1/sync`

One round trip: push local changes, pull remote ones.

```jsonc
// request
{
  "device_id": "…",              // stable per install
  "since": 1753600000000,        // server cursor from the last successful sync, 0 on first
  "transactions": [              // only rows changed since the last successful push
    {
      "client_id": "8f14e45f-…",
      "occurred_at": 1753612345678,
      "amount_paise": 45000,
      "direction": "DEBIT",
      "channel": "UPI",
      "merchant_norm": "swiggy",
      "merchant_raw": "SWIGGY",
      "account_tail": "1234",
      "reference": "512345678901",
      "balance_paise": 950000,
      "category": "Food & Dining",
      "category_source": "USER",
      "updated_at": 1753612400000
    }
  ]
}
```

```jsonc
// response
{
  "cursor": 1753612500000,       // pass back as `since` next time
  "applied": 12,                 // rows the server accepted
  "rejected": [],                // client_ids refused, with a reason
  "transactions": [ /* rows changed server-side since `since`, same shape */ ]
}
```

The cursor is a server clock value, not the client's — clock skew on the phone would otherwise
cause rows to be silently skipped forever.

### `GET /v1/stats?period=this_month`

Aggregates computed server-side. The phone does not need this (it computes its own), but the
dashboard does, and it keeps the two from drifting.

### `POST /v1/classify` *(server-internal)*

Batches merchant strings with no confident category and asks the LLM gateway. Not called by the
phone. See below.

---

## Server-side classification

Runs as a job after sync, never inline.

1. Select `DISTINCT merchant_norm` where `category = 'Other'` or `category_source IN ('SEED','NONE')`
   and the merchant has no `remote_category` cached.
2. Batch **up to 25 per request** to the gateway at `https://llm.deepaksilaych.me/v1/chat/completions`
   with `model=llama-3.3-70b-versatile`, `temperature=0`.
3. Cache the verdict in `merchant_categories`, keyed by `merchant_norm`. **Permanently** — a
   merchant's category does not change, so any given string costs one call ever.
4. Apply to matching transactions as `category_source = 'REMOTE'`, subject to the conflict rules.

Measured on 15 real merchant descriptors: 348 tokens, 0.37s, one call. A full history of ~150
distinct merchants is ten calls.

**The LLM is a layer, not a replacement.** Merchant memory and the seed lexicon are more precise
where they have an answer — testing showed the lexicon correctly placing `bluetokai` as Food and
`swiggy instamart` as Groceries where the LLM guessed Shopping and Food. It runs *below* both,
filling the long tail they cannot reach.

The gateway key lives on the server only. It is never in the APK, which is public.

---

## Postgres schema

Database `umber` on the shared instance.

```sql
CREATE TABLE devices (
  id            TEXT PRIMARY KEY,
  token_hash    TEXT NOT NULL,
  created_at    BIGINT NOT NULL,
  last_seen_at  BIGINT
);

CREATE TABLE transactions (
  client_id       UUID PRIMARY KEY,
  device_id       TEXT NOT NULL REFERENCES devices(id),
  occurred_at     BIGINT NOT NULL,
  amount_paise    BIGINT NOT NULL,
  direction       TEXT   NOT NULL,
  channel         TEXT,
  merchant_norm   TEXT,
  merchant_raw    TEXT,
  account_tail    TEXT,
  reference       TEXT,
  balance_paise   BIGINT,
  category        TEXT   NOT NULL,
  category_source TEXT   NOT NULL,
  updated_at      BIGINT NOT NULL
);

-- The sync cursor scans this constantly; without it every sync is a full table scan.
CREATE INDEX transactions_updated_idx ON transactions (device_id, updated_at);
CREATE INDEX transactions_occurred_idx ON transactions (device_id, occurred_at);

-- Reference numbers are globally unique when present, and are the strongest duplicate guard.
-- Partial, because most rows legitimately have none.
CREATE UNIQUE INDEX transactions_reference_idx
  ON transactions (device_id, reference) WHERE reference IS NOT NULL;

CREATE TABLE merchant_categories (
  merchant_norm TEXT PRIMARY KEY,
  category      TEXT NOT NULL,
  confidence    REAL,
  model         TEXT,
  decided_at    BIGINT NOT NULL
);
```

Money stays integer paise end to end. Never a float, never rupees — a hundredth-of-a-rupee drift
per row silently corrupts every total.

---

## Failure behaviour

Sync is **never load-bearing**. Every failure degrades to "the phone keeps working exactly as the
privacy build does":

- Server unreachable → local classification and stats are unaffected; retry on the next run.
- Gateway down or rate-limited → transactions keep whatever the phone decided.
- Auth rejected → sync disables itself and says so in Settings rather than retrying forever.

Sync runs on WorkManager with a network constraint, not on the ingest path. A dead server must
never delay a transaction appearing on the widget.
