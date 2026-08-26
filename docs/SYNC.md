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
server does not re-run that logic for phone-pushed rows; it trusts what it is given and rejects a
duplicate `client_id` outright, plus a duplicate `reference` as a safety net (see below).

**Dedup is global, not per-device.** This is a single-user ledger — a statement uploaded on the
dashboard covering months already captured by phone SMS must reconcile against *all* prior data,
not just rows from one device. The `reference` uniqueness constraint and the statement-import
same-day-occurrence check (tiers 1 and 3 of the phone's own algorithm, reimplemented server-side
for `POST /v1/statements/import`) both scan the whole `transactions` table regardless of
`device_id`. `device_id` is provenance only — which device/actor last touched a row — never a
dedup boundary.

## Conflict rules

Both sides can change a category. Only one rule matters:

> **A user's own correction always wins.**

Concretely, in descending priority:

| Origin | `category_source` | Beats |
|---|---|---|
| User tapped a category in the app | `USER` | everything |
| Merchant-memory match (auto-applied) | `MEMORY` | everything except `USER` |
| User edited in the dashboard, or an AI agent acting on the user's behalf via the API | `DASHBOARD` | everything except `USER`/`MEMORY` |
| Server LLM classification | `REMOTE` | `MODEL`, `SEED`, `NONE` |
| On-device model / lexicon | `MODEL`, `SEED`, `NONE` | nothing |

`MEMORY` sits at the same tier as `USER` (both beat everything below, and are compared to each
other by `updated_at`): a merchant-memory match only exists because the user confirmed that
merchant once, so it deserves the same trust as a fresh tap. `DASHBOARD` covers both a human
editing in the browser and an AI agent editing via the same authenticated API — they're the same
trust tier, since either represents current human-directed intent, never an automated guess.

Ties within a tier break by `updated_at`, last write wins. The phone never overwrites a `USER` or
`MEMORY` row with a `DASHBOARD`/`REMOTE`/`MODEL` one, which is the failure mode that would make the
feature actively harmful — a lower-trust layer silently undoing a decision the user made by hand.

`updated_at` is set by whoever made the change, in UTC milliseconds.

---

## Device registration

Not a self-serve signup flow — this is a single-user app. The phone is configured once, out of
band, with a `setup_key` (a value you paste into Settings). It calls this once, when sync is first
enabled, and stores the response.

### `POST /v1/devices/register`

```jsonc
// request
{ "setup_key": "…", "label": "Deepak's Pixel" }   // label is optional

// response
{ "device_id": "…", "token": "…" }                 // token is shown once, store it
```

Every later request carries `Authorization: Bearer <token>`.

---

## Actors

Three kinds of caller, all ultimately attributed to a `device_id` for provenance:

| Actor | Auth | `device_id` | Can call |
|---|---|---|---|
| Phone | `Authorization: Bearer <device-token>` from registration above | the real per-install id | `POST /v1/sync` |
| Dashboard | session cookie from `POST /v1/auth/login` (single fixed password) | fixed virtual id `"dashboard"` | `GET/PATCH/POST /v1/transactions` (+ subcategories/bulk-assign/unlink), `POST /v1/statements/import`, `GET /v1/stats` (+ timeline/breakdowns), `/v1/accounts`, `/v1/holdings`, `POST /v1/insights/generate` |
| AI agent | `Authorization: Bearer <agent-token>` (a static token, separate from device tokens) | fixed virtual id `"agent"` | same as Dashboard |

The dashboard and agent share an endpoint surface and a `category_source` tier (`DASHBOARD`) — see
Conflict rules above. FastAPI's auto-generated OpenAPI docs (`/docs`, `/openapi.json`) are the
contract an agent reads to drive these endpoints; there is no separate agent-specific protocol.

---

## Endpoints

Base: `https://finance.deepaksilaych.me`
Auth: `Authorization: Bearer <device-token>` on everything, unless noted otherwise above.

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

Aggregates computed server-side, netting included (see [ARCHITECTURE.md](ARCHITECTURE.md#reimbursement-netting)
for the offset rules — the server ports the same per-merchant offset math the phone uses). The
phone does not need this (it computes its own), but the dashboard does, and it keeps the two from
drifting. `period` is one of `today`, `7d`, `30d`, `this_month`, `this_year`, or pass `from_ms`/
`to_ms` directly for a custom window.

### `GET /v1/transactions`, `GET /v1/transactions/{client_id}`

Dashboard/agent read access, with filters: `category`, `needs_review`, `merchant` (substring),
`occurred_from`/`occurred_to`, `account_id`, `limit`/`offset`. Response rows include `subcategory`
(dashboard-only field — see below) and `account_id`; the phone's own `POST /v1/sync` responses do
not carry `subcategory` (see "Conflict rules" amendment).

### `GET /v1/transactions/subcategories?category=`

Distinct existing `subcategory` strings (optionally scoped to one top-level category), for
dashboard autocomplete so free-text tags don't fragment ("Coffee" vs "coffee").

### `PATCH /v1/transactions/{client_id}`

Edits `category` / `subcategory` / `merchant_raw` / `needs_review` / `account_id`. A category edit
always lands as `category_source: "DASHBOARD"` — see Conflict rules. `subcategory` is free text
(≤60 chars), dashboard/agent-only, layered on top of the frozen 15-item `category` taxonomy —
**never synced to or from the phone**. Unlike every other field on this endpoint, an explicit empty
string clears `subcategory`; every other field treats `null`/omitted as "don't touch."

### `DELETE /v1/transactions/{client_id}/account`

Unlinks a transaction from its account (there's no way to express "clear" through `PATCH`, whose
`null` means "don't touch" for every field including `account_id`).

### `POST /v1/transactions/bulk-assign-account`

`{ client_ids: string[], account_id: string }` → assigns an account to many transactions at once,
e.g. from a dashboard multi-select. Returns `{ updated, not_found }`.

### `POST /v1/transactions`

Manual entry for spend with no SMS or statement trail (e.g. cash). Also lands as `DASHBOARD`.
Accepts an optional `account_id`.

---

## Accounts

A bank account / card registry, entirely server/dashboard-side — the phone has no concept of this,
it only ever wrote a bare `account_tail` (last-4 digits) string onto each transaction. Base path
`/v1/accounts`.

- `GET /v1/accounts` — list, each row includes a live-computed `balance_paise` (opening balance +
  signed sum of linked transactions since `opening_balance_as_of` — computed on read, not cached).
- `POST /v1/accounts` — `{ label, bank_name?, kind, account_tail?, opening_balance_paise?, opening_balance_as_of? }`.
  `kind` is one of `BANK`, `CREDIT_CARD`, `WALLET`, `CASH`, `OTHER`.
- `GET/PATCH/DELETE /v1/accounts/{id}` — delete unlinks (`ON DELETE SET NULL`) rather than deleting
  the transactions themselves; real financial history is never destroyed by an account edit.
- `GET /v1/accounts/{id}/balance?as_of=` — balance as of a specific point in time.
- `POST /v1/accounts/relink` — idempotent backfill: re-runs the auto-link rule (a transaction's
  `account_tail` matches *exactly one* account, never a guess) over every unlinked transaction. Not
  an Alembic migration, since accounts don't exist yet at schema-migration time — call this after
  creating an account to retroactively match its history. Auto-link also fires inline on every new
  or updated transaction (`/v1/sync`, `/v1/statements/import`, `POST /v1/transactions`).

**Known gap**: `/v1/statements/import` does not currently extract an account number from a
statement's preamble, so auto-link is inert for dashboard-uploaded statement rows today (it works
for phone-synced SMS transactions, which do carry `account_tail`). Extracting it from statement
preambles is a documented follow-up, not yet built.

## Investment holdings

A full holdings tracker (units, weighted-average cost, manual valuation updates, gain/loss),
entirely server/dashboard-side, no live market-price API. Base path `/v1/holdings`.

- `GET/POST /v1/holdings`, `GET/PATCH/DELETE /v1/holdings/{id}` — `kind` is one of `STOCK`,
  `MUTUAL_FUND`, `FD`, `GOLD`, `PPF`, `OTHER`. A holding is **unit-tracked or value-tracked**,
  decided by its first `BUY`/`SELL` (not by `kind` — e.g. gold can be tracked either way) and
  enforced on every later one.
- `GET/POST /v1/holdings/{id}/transactions` — an append-only log, `type` one of `BUY`, `SELL`,
  `VALUATION_UPDATE`. No edit/delete on individual rows — a bad entry gets a compensating entry.
  `BUY` updates `units`/`avg_cost_paise` via weighted average (only when `units` is given — a
  value-tracked `BUY` is a pure cash injection, no per-unit price). `SELL` decrements `units`,
  leaves `avg_cost_paise` on the *remaining* units unchanged, and records `realized_gain_paise =
  (sell_price - avg_cost) * units_sold` for unit-tracked sells only (value-tracked partial
  withdrawals have no defensible per-unit cost basis, so it's left null there).
  `VALUATION_UPDATE` only sets `current_value_paise` and feeds the timeline.
- `GET /v1/holdings/summary` — portfolio totals + allocation by `kind`.
- `GET /v1/holdings/timeline` — one point per distinct `VALUATION_UPDATE` timestamp across any
  holding; each point sums every holding's latest real valuation at-or-before that time, or (if
  none yet) its cost-basis-so-far as an explicit proxy, flagging the point `is_estimated` when any
  holding used the proxy. No interpolation — gaps between manual updates are gaps.

---

## Granular stats & insights

All under `/v1/stats`, dashboard/agent or phone-token readable (same `get_actor` dependency as the
existing `GET /v1/stats`), except insights (below), which costs real money per call and is gated
tighter.

- `GET /v1/stats/timeline?granularity=day|week|month&group_by=category|merchant|channel` — bucketed
  time series, IST-anchored (day/week/month boundaries), capped at 400 buckets (400 error past
  that — widen granularity or narrow the range). `group_by=category` nets per bucket (a
  reimbursement split across two buckets won't offset in either bucket's breakdown, even though the
  whole-range `/v1/stats` total offsets it correctly — a real, intentional consequence of bucketing
  net-of-reimbursement data). `group_by=merchant`/`channel` are gross DEBIT sums, not netted.
- `GET /v1/stats/by-merchant`, `GET /v1/stats/by-channel` — grouped aggregates, `direction` filter
  (default `DEBIT`). By-merchant caps at `limit` (default 10) plus an `other_paise` remainder.
- `GET /v1/stats/by-time-pattern?dimension=day_of_week|hour_of_day` — always emits all 7 or 24
  buckets, IST. Weeks are Monday-anchored.
- `GET /v1/stats/by-subcategory` — groups by `(category, subcategory)`.

### `POST /v1/insights/generate?period=`

LLM-written narrative insights — a short summary plus 2-4 concrete suggestions, grounded in a
compact aggregated JSON (period totals, previous-period comparison, top-5 merchant/channel
breakdowns — never raw transaction rows), sent to the same self-hosted gateway `/v1/classify`
(below) is designed around. Synchronous (~0.37s/call measured on that gateway). Cached in
`insights_cache`, keyed by `(period, from_ms, to_ms, stats_hash)` where `stats_hash` is a sha256 of
the outbound JSON — any real data change is automatically a cache miss, no TTL needed. `force_refresh=true`
bypasses the cache. On any gateway failure or unparseable response, **never a 500** — falls back to
a deterministic server-computed summary sentence with empty `suggestions` and `llm_generated: false`;
fallbacks are never cached, so the next call retries the gateway. Gated to dashboard/agent tokens
only (a real per-call cost, unlike the read-only stats endpoints above).

### `POST /v1/statements/import`

Multipart file upload (`.xlsx`/CSV/TSV). Runs the same parsing heuristics as the phone's own
statement importer (header-row scoring, keyword column matching, noon-anchored dates — see
[ARCHITECTURE.md](ARCHITECTURE.md#statement-imports)), reimplemented server-side in Python for
byte-identical `merchant_norm`/dedup keys. Applies dedup tiers 1 (reference) and 3 (same-day
occurrence count) globally, and a lightweight stand-in for the phone's merchant-memory layer:
a row whose `merchant_norm` already has a `USER`/`MEMORY`-sourced category on file elsewhere in the
table is auto-categorized from that (as `DASHBOARD`, not flagged for review); otherwise it lands as
`Other` / `NONE` / `needs_review: true`, same as the phone's own cold start. Responds with
`{ total_rows, inserted, skipped_duplicate, needs_review, problem }`.

### `POST /v1/classify` *(server-internal, not yet implemented)*

Batches merchant strings with no confident category and asks the LLM gateway. Not called by the
phone or the dashboard — a background job. Design below; not built in the current server.

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
  kind          TEXT NOT NULL CHECK (kind IN ('phone','dashboard','agent')),
  label         TEXT,
  token_hash    TEXT NOT NULL,
  created_at    BIGINT NOT NULL,
  last_seen_at  BIGINT
);

-- Seeded once at server startup: the fixed virtual actors 'dashboard' and 'agent' (see Actors
-- above) need a row here too, since transactions.device_id is a real foreign key.

CREATE TABLE accounts (
  id                      TEXT PRIMARY KEY,
  label                   TEXT NOT NULL,
  bank_name               TEXT,
  kind                    TEXT NOT NULL CHECK (kind IN ('BANK','CREDIT_CARD','WALLET','CASH','OTHER')),
  account_tail            TEXT,     -- deliberately NOT unique: two cards can share the same last 4 digits
  opening_balance_paise   BIGINT NOT NULL DEFAULT 0,
  opening_balance_as_of   BIGINT NOT NULL,
  created_at              BIGINT NOT NULL,
  updated_at              BIGINT NOT NULL
);

CREATE INDEX ix_accounts_account_tail ON accounts (account_tail);

CREATE TABLE transactions (
  client_id       TEXT PRIMARY KEY,     -- UUIDv4 from the phone; a handful of pre-migration
                                         -- rows are non-UUID hex strings, so this is TEXT, not UUID
  device_id       TEXT NOT NULL REFERENCES devices(id),
  account_id      TEXT REFERENCES accounts(id) ON DELETE SET NULL,  -- deleting an account unlinks, never deletes
  occurred_at     BIGINT NOT NULL,
  amount_paise    BIGINT NOT NULL,
  direction       TEXT   NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
  channel         TEXT,
  merchant_norm   TEXT,
  merchant_raw    TEXT,
  account_tail    TEXT,
  reference       TEXT,
  balance_paise   BIGINT,
  category        TEXT   NOT NULL,
  category_source TEXT   NOT NULL
                  CHECK (category_source IN ('USER','MEMORY','DASHBOARD','REMOTE','MODEL','SEED','NONE')),
  -- Dashboard/agent-only free-text tag layered on the frozen 15-item `category` taxonomy.
  -- Never synced to or from the phone.
  subcategory     TEXT,
  needs_review    BOOLEAN NOT NULL DEFAULT true,
  updated_at      BIGINT NOT NULL,
  created_at      BIGINT NOT NULL
);

-- The sync cursor scans this constantly; without it every sync is a full table scan.
CREATE INDEX ix_txn_device_updated ON transactions (device_id, updated_at);
CREATE INDEX ix_txn_occurred ON transactions (occurred_at);
CREATE INDEX ix_txn_merchant_norm ON transactions (merchant_norm);
CREATE INDEX ix_txn_needs_review ON transactions (needs_review);
CREATE INDEX ix_txn_account_occurred ON transactions (account_id, occurred_at);
CREATE INDEX ix_txn_subcategory ON transactions (subcategory) WHERE subcategory IS NOT NULL;

-- Global, not per-device — see "Dedup is global, not per-device" above. Partial, because most
-- rows legitimately have none.
CREATE UNIQUE INDEX ux_txn_reference ON transactions (reference) WHERE reference IS NOT NULL;

CREATE TABLE merchant_categories (
  merchant_norm TEXT PRIMARY KEY,
  category      TEXT NOT NULL,
  confidence    REAL,
  model         TEXT,
  decided_at    BIGINT NOT NULL
);

-- Investment holdings tracker (server/dashboard only, no phone concept). units/avg_cost_paise are
-- Numeric, not paise-integer — physical quantities and a derived per-unit rate, not discrete cash.
CREATE TABLE holdings (
  id                    TEXT PRIMARY KEY,
  name                  TEXT NOT NULL,
  kind                  TEXT NOT NULL CHECK (kind IN ('STOCK','MUTUAL_FUND','FD','GOLD','PPF','OTHER')),
  units                 NUMERIC(20,6) CHECK (units IS NULL OR units >= 0),
  avg_cost_paise        NUMERIC(20,4),
  current_value_paise   BIGINT,
  notes                 TEXT,
  created_at            BIGINT NOT NULL,
  updated_at            BIGINT NOT NULL
);

-- Append-only log — BUY/SELL/VALUATION_UPDATE. realized_gain_paise is computed and stored at SELL
-- time (avg_cost drifts afterward, so a later recompute would be wrong); null for value-tracked
-- sells and non-SELL rows.
CREATE TABLE holding_transactions (
  id                    TEXT PRIMARY KEY,
  holding_id            TEXT NOT NULL REFERENCES holdings(id) ON DELETE CASCADE,
  type                  TEXT NOT NULL CHECK (type IN ('BUY','SELL','VALUATION_UPDATE')),
  units                 NUMERIC(20,6) CHECK (units IS NULL OR units > 0),
  price_paise           NUMERIC(20,4),
  amount_paise          BIGINT NOT NULL CHECK (amount_paise >= 0),
  realized_gain_paise   BIGINT,
  occurred_at           BIGINT NOT NULL,
  created_at            BIGINT NOT NULL
);

CREATE INDEX ix_holdingtxn_holding_occurred ON holding_transactions (holding_id, occurred_at);

-- Cache for POST /v1/insights/generate. stats_hash = sha256 of the aggregated JSON sent to the
-- LLM, so any real data change is automatically a cache miss — no TTL/expiry logic needed.
CREATE TABLE insights_cache (
  period          TEXT   NOT NULL,
  from_ms         BIGINT NOT NULL,
  to_ms           BIGINT NOT NULL,
  stats_hash      TEXT   NOT NULL,
  summary         TEXT   NOT NULL,
  suggestions     JSON   NOT NULL,
  model           TEXT   NOT NULL,
  generated_at    BIGINT NOT NULL,
  PRIMARY KEY (period, from_ms, to_ms, stats_hash)
);
```

Money stays integer paise end to end for real cash amounts. Never a float, never rupees for those —
a hundredth-of-a-rupee drift per row silently corrupts every total. `holdings.units`/`avg_cost_paise`
are the one deliberate exception (`NUMERIC`, not integer paise) — see "Investment holdings tracker"
below for why.

Implemented in `server/app/db/models.py` (SQLAlchemy) with Alembic migrations under
`server/migrations/`; this block is the reference, kept in sync by hand.

---

## Failure behaviour

Sync is **never load-bearing**. Every failure degrades to "the phone keeps working exactly as the
privacy build does":

- Server unreachable → local classification and stats are unaffected; retry on the next run.
- Gateway down or rate-limited → transactions keep whatever the phone decided.
- Auth rejected → sync disables itself and says so in Settings rather than retrying forever.

Sync runs on WorkManager with a network constraint, not on the ingest path. A dead server must
never delay a transaction appearing on the widget.
