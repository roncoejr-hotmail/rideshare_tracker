# Transport Ledger — Data Model

SQLite store for a multi-tenant ledger that compares what a household now spends on getting around (rideshare rides, car rentals, EV charging) against what it used to spend on leased cars (lease payments plus fuel). One database file holds every tenant. Every row of domain data belongs to exactly one tenant, and nothing is ever read or written without filtering by that tenant first.

Related documents:

- [API-ENDPOINTS.md](API-ENDPOINTS.md): HTTP contract and the JDK 25 implementation layout.
- [MULTI-USER-AUTH.md](MULTI-USER-AUTH.md): tenants, keys, and the request "door". It is shared with the grocery service and wins over this document on any auth question.
- [sql/V1__schema.sql](sql/V1__schema.sql): the exact DDL below, ready to run.
- [sql/comparison.sql](sql/comparison.sql): the exact comparison query below, ready to run.

## 1. Conventions

| Topic | Rule |
| --- | --- |
| Engine | SQLite 3.35 or newer (generated columns, `RETURNING`). The bundled engine in current `org.xerial:sqlite-jdbc` is far newer. |
| Connection pragmas | Every connection runs `PRAGMA foreign_keys = ON;` and `PRAGMA busy_timeout = 5000;` before first use. |
| Journal | `PRAGMA journal_mode = WAL;` once at startup (persists in the file). |
| Keys | Surrogate `INTEGER PRIMARY KEY AUTOINCREMENT` on every table. Surrogates never appear in the API except `tenantId` and `keyId` on admin routes. Clients address rows by **natural key**. |
| Money | Integer **cents** (`*_cents INTEGER`). Never `REAL`. The API converts to and from decimal JSON numbers with exactly 2 decimal places. |
| Currency | ISO 4217 code, 3 uppercase letters, stored on every money-bearing row. The comparison counts only rows in the tenant's currency (`tenant_settings.currency`, default `USD`). Rows in other currencies are stored, listed, and counted as "excluded", but never converted or added. |
| Calendar dates | `TEXT` `YYYY-MM-DD`, validated in SQL by `GLOB` shape plus `date(x) = x` (rejects `2026-02-30`). |
| Instants | `TEXT` ISO-8601 UTC with `Z`, e.g. `2026-11-24T16:24:00Z`. Audit columns use `strftime('%Y-%m-%dT%H:%M:%fZ','now')` (millisecond precision). |
| Enums | Lowercase `TEXT` with a `CHECK (... IN (...))`. |
| Booleans | `INTEGER` 0/1 with `CHECK (x IN (0,1))`. JSON `true`/`false` in the API. |
| Audit | `created_at` defaults on insert. The application sets `updated_at` on every update (no triggers). |
| Tenant column | Every **top-level** domain table has `tenant_id NOT NULL REFERENCES tenant ON DELETE CASCADE`. **Child** tables (only `rental_adjustment`) have no `tenant_id`; they are reached through their tenant-owned parent. |
| Views | None. Views cannot take a tenant parameter, so all reads are parameterized queries whose first predicate is `tenant_id = ?`. |

## 2. Entity overview

```text
tenant 1───* tenant_key
tenant 1───1 tenant_settings
tenant 1───* ride
tenant 1───* rental 1───* rental_adjustment
tenant 1───* recurring_cost
```

| Table | Owner | Natural key (unique) | Purpose |
| --- | --- | --- | --- |
| `tenant` | — | `tenant_id` | A person or household. Identity comes from the API key. |
| `tenant_key` | tenant | `key_hash` (global) | Hashed API keys. See MULTI-USER-AUTH.md. |
| `tenant_settings` | tenant | `tenant_id` | Comparison start date, currency, home region label, personal tag, time zone. |
| `ride` | tenant | `(tenant_id, email_message_id)` | One personal rideshare trip, from its receipt email (subject must carry the personal tag). |
| `rental` | tenant | `(tenant_id, company, confirmation_no)` | One car rental, gross cost. |
| `rental_adjustment` | rental | `(rental_pk, line_no)` | Rebates, refunds, reimbursements, holds tied to a rental. |
| `recurring_cost` | tenant | `(tenant_id, name, effective_from)` | Lease payments, fuel avoided (baseline) and EV charging (current), weekly or monthly. |

## 3. DDL (schema version 1)

This is the complete content of `sql/V1__schema.sql`. Apply it in one transaction on an empty database.

```sql
-- transport-ledger schema, version 1 (PRAGMA user_version = 1)
-- Connection-level settings (every connection, not stored in the file):
--   PRAGMA foreign_keys = ON;  PRAGMA busy_timeout = 5000;
-- Database-level setting (once, at startup, persists in the file):
--   PRAGMA journal_mode = WAL;

-- ---------------------------------------------------------------- auth
CREATE TABLE tenant (
    tenant_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    is_operator INTEGER NOT NULL DEFAULT 0 CHECK (is_operator IN (0,1)),
    is_enabled  INTEGER NOT NULL DEFAULT 1 CHECK (is_enabled IN (0,1)),
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE tenant_key (
    key_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id   INTEGER NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    key_hash    TEXT    NOT NULL UNIQUE
                CHECK (length(key_hash) = 64 AND key_hash NOT GLOB '*[^0-9a-f]*'),
    label       TEXT    NOT NULL CHECK (length(label) BETWEEN 1 AND 40),
    is_enabled  INTEGER NOT NULL DEFAULT 1 CHECK (is_enabled IN (0,1)),
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX ix_tenant_key_tenant ON tenant_key (tenant_id);

-- ---------------------------------------------------------------- settings
CREATE TABLE tenant_settings (
    tenant_id        INTEGER PRIMARY KEY REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    comparison_start TEXT NOT NULL
                     CHECK (comparison_start GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                            AND date(comparison_start) = comparison_start),
    currency         TEXT NOT NULL DEFAULT 'USD'
                     CHECK (length(currency) = 3 AND currency NOT GLOB '*[^A-Z]*'),
    home_region      TEXT NOT NULL CHECK (length(home_region) BETWEEN 1 AND 80),
    personal_tag     TEXT NOT NULL DEFAULT '[Personal]' CHECK (length(personal_tag) BETWEEN 1 AND 40),
    time_zone        TEXT NOT NULL DEFAULT 'UTC' CHECK (length(time_zone) BETWEEN 1 AND 64),
    created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

-- ---------------------------------------------------------------- rides
-- No purpose column: the API stores only rides whose subject carries the personal tag.
CREATE TABLE ride (
    ride_pk            INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id          INTEGER NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    email_message_id   TEXT    NOT NULL CHECK (length(email_message_id) BETWEEN 1 AND 512),
    provider           TEXT    NOT NULL CHECK (length(provider) BETWEEN 1 AND 40),
    region             TEXT    NOT NULL CHECK (region  IN ('local','travel')),
    ride_date          TEXT    NOT NULL
                       CHECK (ride_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                              AND date(ride_date) = ride_date),
    pickup_utc         TEXT,
    dropoff_utc        TEXT,
    pickup_addr        TEXT    CHECK (pickup_addr  IS NULL OR length(pickup_addr)  <= 200),
    dropoff_addr       TEXT    CHECK (dropoff_addr IS NULL OR length(dropoff_addr) <= 200),
    currency           TEXT    NOT NULL DEFAULT 'USD'
                       CHECK (length(currency) = 3 AND currency NOT GLOB '*[^A-Z]*'),
    fare_cents         INTEGER NOT NULL CHECK (fare_cents >= 0),
    tip_cents          INTEGER NOT NULL DEFAULT 0 CHECK (tip_cents >= 0),
    total_cents        INTEGER GENERATED ALWAYS AS (fare_cents + tip_cents) STORED,
    payment_last4      TEXT    CHECK (payment_last4 IS NULL OR
                                      (length(payment_last4) = 4 AND payment_last4 NOT GLOB '*[^0-9]*')),
    rider              TEXT    CHECK (rider IS NULL OR length(rider) <= 80),
    -- Required: must contain the tenant's personal_tag (checked by the API; a CHECK
    -- cannot read tenant_settings). Every stored ride is a personal ride.
    email_subject      TEXT    NOT NULL CHECK (length(email_subject) BETWEEN 1 AND 300),
    email_received_utc TEXT,
    notes              TEXT    CHECK (notes IS NULL OR length(notes) <= 2000),
    created_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    UNIQUE (tenant_id, email_message_id)
);
CREATE INDEX ix_ride_tenant_date   ON ride (tenant_id, ride_date);
CREATE INDEX ix_ride_tenant_filter ON ride (tenant_id, region, currency, ride_date);

-- ---------------------------------------------------------------- rentals
CREATE TABLE rental (
    rental_pk          INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id          INTEGER NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    company            TEXT    NOT NULL COLLATE NOCASE CHECK (length(company) BETWEEN 1 AND 40),
    confirmation_no    TEXT    NOT NULL CHECK (length(confirmation_no) BETWEEN 1 AND 64),
    booking_channel    TEXT    CHECK (booking_channel IS NULL OR length(booking_channel) <= 60),
    start_date         TEXT    NOT NULL
                       CHECK (start_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                              AND date(start_date) = start_date),
    end_date           TEXT    NOT NULL
                       CHECK (end_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                              AND date(end_date) = end_date),
    location           TEXT    CHECK (location IS NULL OR length(location) <= 200),
    purpose            TEXT    NOT NULL DEFAULT 'personal' CHECK (purpose IN ('personal','business')),
    region             TEXT    NOT NULL CHECK (region IN ('local','travel')),
    currency           TEXT    NOT NULL DEFAULT 'USD'
                       CHECK (length(currency) = 3 AND currency NOT GLOB '*[^A-Z]*'),
    gross_cents        INTEGER NOT NULL CHECK (gross_cents >= 0),
    payment_last4      TEXT    CHECK (payment_last4 IS NULL OR
                                      (length(payment_last4) = 4 AND payment_last4 NOT GLOB '*[^0-9]*')),
    email_message_id   TEXT    CHECK (email_message_id IS NULL OR length(email_message_id) <= 512),
    email_subject      TEXT    CHECK (email_subject IS NULL OR length(email_subject) <= 300),
    notes              TEXT    CHECK (notes IS NULL OR length(notes) <= 2000),
    created_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    CHECK (end_date >= start_date),
    UNIQUE (tenant_id, company, confirmation_no)
);
CREATE INDEX ix_rental_tenant_date ON rental (tenant_id, start_date);

-- Child of rental. No tenant_id of its own: always reached through rental.
CREATE TABLE rental_adjustment (
    adjustment_pk    INTEGER PRIMARY KEY AUTOINCREMENT,
    rental_pk        INTEGER NOT NULL REFERENCES rental(rental_pk) ON DELETE CASCADE,
    line_no          INTEGER NOT NULL CHECK (line_no >= 1),
    kind             TEXT    NOT NULL CHECK (kind IN ('rebate','refund','reimbursement','hold')),
    amount_cents     INTEGER NOT NULL CHECK (amount_cents > 0),
    affects_net      INTEGER NOT NULL CHECK (affects_net IN (0,1)),
    effective_date   TEXT    NOT NULL
                     CHECK (effective_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                            AND date(effective_date) = effective_date),
    hold_released    INTEGER CHECK (hold_released IN (0,1)),
    email_message_id TEXT    CHECK (email_message_id IS NULL OR length(email_message_id) <= 512),
    notes            TEXT    CHECK (notes IS NULL OR length(notes) <= 2000),
    CHECK ((kind IN ('rebate','refund')        AND affects_net = 1) OR
           (kind IN ('reimbursement','hold')   AND affects_net = 0)),
    CHECK ((kind = 'hold' AND hold_released IS NOT NULL) OR
           (kind <> 'hold' AND hold_released IS NULL)),
    UNIQUE (rental_pk, line_no)
);
CREATE INDEX ix_adj_rental ON rental_adjustment (rental_pk);

-- ---------------------------------------------------------------- recurring costs
CREATE TABLE recurring_cost (
    cost_pk        INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id      INTEGER NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    name           TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    side           TEXT    NOT NULL CHECK (side   IN ('baseline','current')),
    period         TEXT    NOT NULL CHECK (period IN ('weekly','monthly')),
    amount_cents   INTEGER NOT NULL CHECK (amount_cents >= 0),
    effective_from TEXT    NOT NULL
                   CHECK (effective_from GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                          AND date(effective_from) = effective_from),
    effective_to   TEXT
                   CHECK (effective_to IS NULL OR
                          (effective_to GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
                           AND date(effective_to) = effective_to)),
    notes          TEXT    CHECK (notes IS NULL OR length(notes) <= 2000),
    created_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    updated_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    UNIQUE (tenant_id, name, effective_from)
);
CREATE INDEX ix_cost_tenant ON recurring_cost (tenant_id, effective_from);

PRAGMA user_version = 1;
```

## 4. Table notes

### 4.1 `tenant`, `tenant_key`

Identical in shape and behavior to the grocery service; [MULTI-USER-AUTH.md](MULTI-USER-AUTH.md) is the authority.

- `tenant.is_operator = 1` marks the operator (the first tenant, created by bootstrap). Exactly one operator exists in normal operation; no route can set `is_operator`.
- `tenant_key.key_hash` is the lowercase hex SHA-256 of the UTF-8 bytes of the secret. The secret itself is never stored, logged, or returned after creation.
- `tenant_key.label` (e.g. `desktop`, `agent`) is descriptive only and grants no scope.
- There is no tenant delete. `is_enabled = 0` is the off switch for a tenant or a key. `ON DELETE CASCADE` exists only so a manual, offline cleanup removes everything a tenant owns.

### 4.2 `tenant_settings`

One row per tenant; the comparison cannot run without it.

| Column | Meaning |
| --- | --- |
| `comparison_start` | First day that counts. Usually the day after the leases ended. Rows dated before it are stored but ignored by the comparison. |
| `currency` | The single currency of the comparison. Default `USD`. Changing it does not convert anything; rows in the old currency simply stop counting. |
| `home_region` | Free-text label for what "local" means for this tenant (e.g. `DC/MD/VA`). Display only. The `region` column on rides and rentals is what the rules use. |
| `personal_tag` | Subject-line tag that marks a personal trip in this tenant's receipts (default `[Personal]`). The API accepts a ride only if its `emailSubject` contains this tag (case-insensitive); see API-ENDPOINTS.md §5.1. If the tenant has no settings yet, `[Personal]` applies. Changing the tag later does not re-check stored rides. |
| `time_zone` | IANA zone (e.g. `America/New_York`), validated with `java.time.ZoneId.of`. Used only to compute the default `asOf` ("today") for the comparison. |

### 4.3 `ride`

- **Natural key:** `email_message_id`, the provider message id of the receipt email (for Outlook/Gmail, the RFC 5322 `Message-ID` or the provider's stable message id). If a ride has no email, the client must still supply a stable unique string (e.g. `manual:2026-11-24:1`). Upserts never duplicate a receipt.
- **`ride_date`** is the calendar date of the trip **as printed on the receipt** (the rider's local date). The comparison uses `ride_date`, never `pickup_utc`, so a late-evening ride is not pushed into the next UTC day. `pickup_utc` and `dropoff_utc` are optional detail.
- **Money:** `fare_cents` is what was charged excluding tip, after any credits or promotions; `tip_cents` is the tip; `total_cents` (generated) is the amount charged. The API takes `total` and `tip` and stores `fare_cents = total − tip`.
- **Personal only (decided):** the table stores personal rides only, so it has no `purpose` column. `email_subject` is required, and the API rejects any ride whose subject does not contain the tenant's `personal_tag`. Clients cannot bypass this; a `purpose` field sent to the API is ignored like any unknown field. Business trips are simply never written.
- **`region`:** `local` (inside the tenant's home region) or `travel` (anywhere else, e.g. rides during a trip). Only `local` counts. Rides taken while traveling would have happened with or without the leased cars, so they are not part of the comparison.
- `payment_last4` is the last 4 digits only; never store full card numbers. `rider` is an optional display name for a household member.

### 4.4 `rental`

- **Natural key:** `(company, confirmation_no)`. `company` uses `COLLATE NOCASE`, so `Avis` and `avis` are the same key; the first-written spelling is kept.
- `gross_cents` is the final charged amount from the receipt (not the booking estimate) and **excludes** holds and deposits.
- `start_date` / `end_date` are pickup and return dates (`end_date >= start_date`).
- **Attribution (decided):** the whole net cost of a rental is attributed to its **`start_date`**. It is not spread across rental days, and adjustments do not move it to their own dates. A rental from 2026-09-29 to 2026-10-02 counts entirely in September.
- `purpose` (`personal`/`business`) exists on rentals only and defaults to `personal`, because rental confirmations usually carry no profile tag. Rentals have no subject requirement.

### 4.5 `rental_adjustment` (child of `rental`)

Money that changes, or appears to change, what a rental cost. Rows have no `tenant_id`; ownership is always checked through `rental.tenant_id`.

| `kind` | `affects_net` | Meaning | Example |
| --- | --- | --- | --- |
| `rebate` | 1 | Value earned back because of the rental (credit, points cash-out). | $50 airline travel credit earned by the rental. |
| `refund` | 1 | Money returned on the rental charge itself. | Partial refund for a late pickup. |
| `reimbursement` | 0 | Repayment of an out-of-pocket cost that is **not** in `gross_cents`. Net zero. | Host reimbursed a $16 parking fee the renter paid. |
| `hold` | 0 | Authorization or deposit; never a cost. `hold_released` is required (0/1). | $300 refundable security deposit. |

- `amount_cents` is always positive. `affects_net` is derived from `kind` (enforced by `CHECK`); the API never accepts it as input.
- **Rental net** = `gross_cents − SUM(amount_cents WHERE affects_net = 1)`. The API rejects writes that would make net negative.
- `line_no` starts at 1, is unique per rental, and orders the adjustments. A full write of a rental replaces its whole adjustment set.

### 4.6 `recurring_cost`

| Column | Meaning |
| --- | --- |
| `side` | `baseline` = the old world (lease payments, fuel no longer bought). `current` = recurring costs of the new world (EV charging). |
| `period` | `monthly` or `weekly`. |
| `amount_cents` | Amount per period. |
| `effective_from` / `effective_to` | Inclusive date range. `effective_to` NULL = ongoing. |

- A price change is a **new row**: set `effective_to` on the old row and add a row with a new `effective_from`. History is never rewritten.
- The API rejects (409) a row whose range overlaps another row with the same `name` in the same tenant. SQLite cannot express that constraint, so the store checks it inside the write transaction.
- Recurring costs are always in the tenant's currency; they have no currency column.

## 5. Tenant scoping rules (non-negotiable)

1. The tenant id comes **only** from the authenticated principal (`RequestContext.current().tenantId()`), never from a path, query string, or body.
2. Every statement that touches `ride`, `rental`, `recurring_cost`, or `tenant_settings` has `tenant_id = ?` as its first predicate, bound to the principal's tenant.
3. Every statement that touches `rental_adjustment` joins or sub-selects through `rental` with `rental.tenant_id = ?`. Example:

   ```sql
   SELECT a.* FROM rental_adjustment a
     JOIN rental r ON r.rental_pk = a.rental_pk
    WHERE r.tenant_id = ? AND r.company = ? AND r.confirmation_no = ?
    ORDER BY a.line_no;
   ```

4. Updates and deletes include `tenant_id = ?` in their own `WHERE`, even after a prior ownership `SELECT`.
5. A row owned by another tenant is indistinguishable from a missing row: same `404`, same body, same code path.
6. Admin statements on `tenant` and `tenant_key` are the only cross-tenant statements, and only operator routes and the door may run them.
7. No views, no triggers that read across tenants, no `ATTACH`.

## 6. Comparison model

### 6.1 Definitions

For a tenant with settings `(comparison_start = S, currency = C)` and a request date `asOf = A` (inclusive):

- **Window:** every day `d` with `S ≤ d ≤ A`. If `A < S`, the window is empty and the result has no months.
- **Months:** each calendar month that intersects the window, in order. Month `m` has window `[max(first day of m, S), min(last day of m, A)]` and `D_m` = number of days in that calendar month (28 to 31).
- **Recurring daily amount** of cost `c` on day `d` (only when `effective_from ≤ d ≤ effective_to`, open end = ongoing):
  - `monthly`: `amount / D_m` where `m` is the month containing `d`.
  - `weekly`: `amount / 7`.
- **Old cost (month)** = Σ over days in the month window of the daily amounts of all `baseline` costs.
- **Recurring new (month)** = the same sum over `current` costs.
- **Rides (month)** = Σ `total_cents` of rides with `region = 'local'`, `currency = C`, and `ride_date` in the month window (every stored ride is personal).
- **Rentals (month)** = Σ rental net of rentals with `purpose = 'personal'`, `region = 'local'`, `currency = C`, and `start_date` in the month window.
- **New cost (month)** = rides + rentals + recurring new.
- **Saved (month)** = old cost − new cost (may be negative).
- **Excluded counts (month):** rides dated in the month window that fail region or currency; rentals that fail purpose, region, or currency. Rows before `S` or after `A` are outside the window and are not counted at all.
- **Cumulative** values are running sums of the **rounded** monthly values, so the monthly table always adds up to the totals.

### 6.2 Exact arithmetic and rounding

Rides and rentals are already whole cents. Recurring amounts are fractions. For month `m`, put every recurring daily amount over the common denominator `7 × D_m`:

- monthly cost, `d` active days: `amount × d / D_m = (amount × d × 7) / (7 × D_m)`
- weekly cost, `d` active days: `amount × d / 7 = (amount × d × D_m) / (7 × D_m)`

So each side of each month is one exact integer numerator `N` over `7 × D_m`. Round **once per month per side** with round-half-even:

```text
q = N div den, r = N mod den          (N >= 0, den = 7 * D_m)
if 2r > den:  q + 1
if 2r = den:  q + (q mod 2)           -- ties go to the even cent
else:         q
```

Java equivalent: `BigDecimal.valueOf(N).divide(BigDecimal.valueOf(den), 0, RoundingMode.HALF_EVEN).longValueExact()`.
Do not round per cost, per day, or per row. Do not use `double` anywhere in this path. Numerators fit easily in a signed 64-bit integer (amount ≤ 100,000,000 cents × 31 days × 31).

### 6.3 The query

`sql/comparison.sql`. Parameters: `?1` = principal's tenant id, `?2` = `asOf` (`YYYY-MM-DD`). Every source table is filtered by `tenant_id = ?1` (adjustments through their rental). It returns one row per month with all amounts already rounded; the application adds the cumulative columns and the totals.

```sql
-- Monthly comparison for ONE tenant.
--   ?1 = tenant_id  (from the bound Principal, never from the request)
--   ?2 = asOf       ('YYYY-MM-DD', inclusive)
-- Returns one row per calendar month from the month of comparison_start through the
-- month of asOf. Returns zero rows if the tenant has no settings or asOf < comparison_start.
-- Rides count when region = 'local' and currency = tenant currency (every stored ride is
-- personal by construction). Rentals also need purpose = 'personal'.
-- All money is integer cents. Recurring amounts are summed as exact fractions over the
-- common denominator 7 * days_in_month and rounded half-even ONCE per month and side.
WITH RECURSIVE
s AS (
    SELECT comparison_start AS cs, currency AS cur
      FROM tenant_settings
     WHERE tenant_id = ?1
       AND comparison_start <= ?2
),
months(m_start) AS (
    SELECT date(cs, 'start of month') FROM s
    UNION ALL
    SELECT date(m_start, '+1 month') FROM months
     WHERE date(m_start, '+1 month') <= ?2
),
win AS (
    SELECT m.m_start,
           max(m.m_start, s.cs)                                   AS w_from,
           min(date(m.m_start, '+1 month', '-1 day'), ?2)         AS w_to,
           CAST(strftime('%d', date(m.m_start, '+1 month', '-1 day')) AS INTEGER) AS dim,
           s.cur
      FROM months m CROSS JOIN s
),
cost_days AS (
    SELECT w.m_start, rc.side, rc.period, rc.amount_cents, w.dim,
           max(0, CAST(round(julianday(min(w.w_to, coalesce(rc.effective_to, '9999-12-31')))
                           - julianday(max(w.w_from, rc.effective_from))) AS INTEGER) + 1) AS d
      FROM recurring_cost rc
      JOIN win w
        ON rc.effective_from <= w.w_to
       AND coalesce(rc.effective_to, '9999-12-31') >= w.w_from
     WHERE rc.tenant_id = ?1
),
-- numerator over denominator 7*dim:  monthly: a*d/dim = a*d*7/(7*dim);  weekly: a*d/7 = a*d*dim/(7*dim)
cost_num AS (
    SELECT m_start,
           sum(CASE WHEN side = 'baseline' THEN
                    CASE period WHEN 'monthly' THEN amount_cents * d * 7 ELSE amount_cents * d * dim END
               ELSE 0 END) AS old_num,
           sum(CASE WHEN side = 'current' THEN
                    CASE period WHEN 'monthly' THEN amount_cents * d * 7 ELSE amount_cents * d * dim END
               ELSE 0 END) AS cur_num
      FROM cost_days
     GROUP BY m_start
),
rides AS (
    SELECT w.m_start,
           sum(CASE WHEN r.region = 'local' AND r.currency = w.cur
                    THEN r.total_cents ELSE 0 END)                                    AS ride_cents,
           sum(CASE WHEN r.region = 'local' AND r.currency = w.cur
                    THEN 1 ELSE 0 END)                                                AS ride_count,
           sum(CASE WHEN r.region = 'local' AND r.currency = w.cur
                    THEN 0 ELSE 1 END)                                                AS excluded_ride_count
      FROM ride r
      JOIN win w ON r.ride_date BETWEEN w.w_from AND w.w_to
     WHERE r.tenant_id = ?1
     GROUP BY w.m_start
),
rental_net AS (
    SELECT rn.rental_pk, rn.start_date, rn.purpose, rn.region, rn.currency,
           rn.gross_cents - coalesce((SELECT sum(a.amount_cents)
                                        FROM rental_adjustment a
                                       WHERE a.rental_pk = rn.rental_pk
                                         AND a.affects_net = 1), 0) AS net_cents
      FROM rental rn
     WHERE rn.tenant_id = ?1
),
rentals AS (
    SELECT w.m_start,
           sum(CASE WHEN x.purpose = 'personal' AND x.region = 'local' AND x.currency = w.cur
                    THEN x.net_cents ELSE 0 END)                                      AS rental_cents,
           sum(CASE WHEN x.purpose = 'personal' AND x.region = 'local' AND x.currency = w.cur
                    THEN 1 ELSE 0 END)                                                AS rental_count,
           sum(CASE WHEN x.purpose = 'personal' AND x.region = 'local' AND x.currency = w.cur
                    THEN 0 ELSE 1 END)                                                AS excluded_rental_count
      FROM rental_net x
      JOIN win w ON x.start_date BETWEEN w.w_from AND w.w_to
     GROUP BY w.m_start
),
joined AS (
    SELECT w.m_start, w.w_from, w.w_to, w.dim, 7 * w.dim AS den,
           coalesce(c.old_num, 0) AS old_num,
           coalesce(c.cur_num, 0) AS cur_num,
           coalesce(r.ride_cents, 0) AS ride_cents,
           coalesce(r.ride_count, 0) AS ride_count,
           coalesce(r.excluded_ride_count, 0) AS excluded_ride_count,
           coalesce(t.rental_cents, 0) AS rental_cents,
           coalesce(t.rental_count, 0) AS rental_count,
           coalesce(t.excluded_rental_count, 0) AS excluded_rental_count
      FROM win w
      LEFT JOIN cost_num c ON c.m_start = w.m_start
      LEFT JOIN rides    r ON r.m_start = w.m_start
      LEFT JOIN rentals  t ON t.m_start = w.m_start
),
rounded AS (
    -- round half-even of n/den for n >= 0:  q = n/den, rem = n%den
    SELECT j.*,
           (old_num / den) + CASE WHEN 2 * (old_num % den) > den THEN 1
                                  WHEN 2 * (old_num % den) = den THEN (old_num / den) % 2
                                  ELSE 0 END AS old_cents,
           (cur_num / den) + CASE WHEN 2 * (cur_num % den) > den THEN 1
                                  WHEN 2 * (cur_num % den) = den THEN (cur_num / den) % 2
                                  ELSE 0 END AS recurring_new_cents
      FROM joined j
)
SELECT strftime('%Y-%m', m_start)                         AS month,
       w_from                                             AS window_from,
       w_to                                               AS window_to,
       CAST(round(julianday(w_to) - julianday(w_from)) AS INTEGER) + 1 AS days,
       old_cents,
       ride_cents,
       ride_count,
       rental_cents,
       rental_count,
       recurring_new_cents,
       ride_cents + rental_cents + recurring_new_cents   AS new_cents,
       old_cents - (ride_cents + rental_cents + recurring_new_cents) AS saved_cents,
       excluded_ride_count,
       excluded_rental_count
  FROM rounded
 ORDER BY m_start;
```

Result columns:

| Column | Type | Meaning |
| --- | --- | --- |
| `month` | TEXT `YYYY-MM` | Calendar month. |
| `window_from`, `window_to` | TEXT date | Days of this month inside the window (inclusive). |
| `days` | INTEGER | Days in the month window. |
| `old_cents` | INTEGER | Baseline, rounded half-even. |
| `ride_cents`, `ride_count` | INTEGER | Counted rides. |
| `rental_cents`, `rental_count` | INTEGER | Counted rental nets. |
| `recurring_new_cents` | INTEGER | Current-side recurring, rounded half-even. |
| `new_cents` | INTEGER | `ride_cents + rental_cents + recurring_new_cents`. |
| `saved_cents` | INTEGER | `old_cents − new_cents`. |
| `excluded_ride_count`, `excluded_rental_count` | INTEGER | Rows in the window that failed the rules (rides: region or currency; rentals: purpose, region, or currency). |

### 6.4 Worked example (acceptance data)

Configuration (the real configuration for tenant 1):

| Name | Side | Period | Amount | From | To |
| --- | --- | --- | --- | --- | --- |
| Lease A | baseline | monthly | $645.00 | 2026-07-26 | — |
| Lease B | baseline | monthly | $727.92 | 2026-07-26 | — |
| Fuel avoided | baseline | weekly | $42.00 | 2026-07-26 | — |
| EV charging | current | weekly | $8.00 | 2026-07-26 | — |

`comparison_start = 2026-07-26`, currency `USD`.

**July 26–31, 2026 baseline** (6 days, `D = 31`, `den = 217`):

```text
leases:  (64500 + 72792) × 6 × 7  = 137292 × 42  = 5,766,264
fuel:     4200 × 6 × 31                          =   781,200
N                                                = 6,547,464
N / 217 = 30,172.645...  cents   (= 6/31 × $1,372.92 + 6/7 × $42.00 = $265.7264... + $36.00)
q = 30172, r = 140, 2r = 280 > 217  → 30,173 cents = $301.73
```

**July EV charging:** `800 × 6 × 31 = 148,800`; `148,800 / 217 = 685.71...` → **$6.86**.

**Full months:** August (31 days) baseline = $1,372.92 + 31/7 × $42 = **$1,558.92**; September (30 days) = $1,372.92 + $180.00 = **$1,552.92**. EV charging: August $35.43 (3,542.857... cents), September $34.29 (3,428.571... cents).

**November 1–25** (25 of 30 days): baseline = 25/30 × $1,372.92 + 25/7 × $42 = $1,144.10 + $150.00 = **$1,294.10**; EV = 25/7 × $8 = **$28.57**.

The full expected output for tenant 1 with the seed data is in [seed/README.md](seed/README.md). A small synthetic fixture that exercises every rule (including a half-even tie) is in [seed/fixture-synthetic.sql](seed/fixture-synthetic.sql), with expected results in API-ENDPOINTS.md §10.

## 7. Indexes

| Index | Serves |
| --- | --- |
| `tenant_key.key_hash` UNIQUE (implicit) | The door's hash lookup. |
| `ix_tenant_key_tenant (tenant_id)` | Admin listing; "last enabled operator key" count. |
| `ix_ride_tenant_date (tenant_id, ride_date)` | Ride lists by date; comparison month joins. |
| `ix_ride_tenant_filter (tenant_id, region, currency, ride_date)` | Filtered ride lists. |
| `UNIQUE (tenant_id, email_message_id)` | Ride upsert lookup. |
| `ix_rental_tenant_date (tenant_id, start_date)` | Rental lists; comparison. |
| `UNIQUE (tenant_id, company, confirmation_no)` | Rental upsert lookup. |
| `ix_adj_rental (rental_pk)` | Loading a rental's adjustments. |
| `ix_cost_tenant (tenant_id, effective_from)` | Recurring cost lists; comparison. |

## 8. Migrations and versioning

- The schema version is `PRAGMA user_version`. Version 0 = empty file. This document defines version **1**.
- Migrations are SQL resources named `V<n>__<description>.sql` (classpath `db/migration/`), applied in order at startup **before the HTTP port opens**:
  1. Open a connection, set the connection pragmas, set `journal_mode = WAL`.
  2. Read `user_version` = `v`. If `v` is greater than the highest known migration, log an error and exit non-zero (never run against a newer schema).
  3. For each migration `n > v`: `BEGIN IMMEDIATE;` run the script; `PRAGMA user_version = n;` `COMMIT;`. Any failure rolls back and the process exits non-zero.
  4. Run `PRAGMA foreign_key_check;` and `PRAGMA integrity_check;` once; abort startup on any finding.
- Migrations are forward-only. Never edit a released migration; add `V2__...`.
- Backups: use `VACUUM INTO '<path>'` or the SQLite online backup API while the server runs. Do not copy the `.db` file alone while in WAL mode.
