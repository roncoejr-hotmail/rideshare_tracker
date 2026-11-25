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
